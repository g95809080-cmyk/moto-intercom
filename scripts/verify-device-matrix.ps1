param(
    [string[]]$Serials = @("9688fa60", "efcb9031"),
    [string]$Adb = "",
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk"
)

$ErrorActionPreference = "Stop"

function Resolve-AdbPath {
    param([string]$ExplicitAdb)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitAdb)) {
        return $ExplicitAdb
    }
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    $localProps = Join-Path (Split-Path -Parent $PSScriptRoot) "local.properties"
    if (Test-Path -LiteralPath $localProps -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProps -Encoding UTF8 |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=\s*(.+)$' } |
            Select-Object -First 1
        if ($sdkLine -match '^\s*sdk\.dir\s*=\s*(.+)$') {
            $sdkDir = $Matches[1].Trim() -replace '\\:', ':' -replace '\\\\', '\' -replace '/', '\'
            if (-not [string]::IsNullOrWhiteSpace($sdkDir)) {
                $candidates += (Join-Path $sdkDir "platform-tools\adb.exe")
            }
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $onPath -and -not [string]::IsNullOrWhiteSpace($onPath.Source)) {
        return $onPath.Source
    }
    return ""
}

$Adb = Resolve-AdbPath -ExplicitAdb $Adb
if ([string]::IsNullOrWhiteSpace($Adb) -or -not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw "ADB not found: $Adb"
}
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "APK not found: $Apk"
}
if ($Serials.Count -eq 0) {
    throw "At least one ADB serial is required"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path "build\device-verification" $stamp
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Invoke-AdbCapture {
    param(
        [string]$Serial,
        [string]$Name,
        [string[]]$CommandArgs
    )

    $path = Join-Path $out "$Serial-$Name.txt"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Adb -s $Serial @CommandArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    Set-Content -Encoding utf8 -LiteralPath $path -Value @($output)
    if ($exitCode -ne 0) {
        throw "ADB failed: $Serial $Name"
    }
}

foreach ($serial in $Serials) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $state = (& $Adb -s $serial get-state 2>&1 | Out-String).Trim()
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0 -or $state -ne "device") {
        throw "ADB device not ready: $serial state=$state"
    }

    Invoke-AdbCapture $serial "install" @("install", "-r", $Apk)
    Invoke-AdbCapture $serial "clear-logcat" @("logcat", "-c")
    Invoke-AdbCapture $serial "force-stop" @(
        "shell", "am", "force-stop", "com.kuma.motointercom"
    )
    Invoke-AdbCapture $serial "launch" @(
        "shell", "monkey", "-p", "com.kuma.motointercom", "1"
    )
    Start-Sleep -Seconds 2
    Invoke-AdbCapture $serial "service" @(
        "shell", "dumpsys", "activity", "services", "com.kuma.motointercom"
    )
    Invoke-AdbCapture $serial "audio" @("shell", "dumpsys", "audio")
    $uiDumpReady = $false
    $uiDumpChecks = @()
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Invoke-AdbCapture $serial "remove-ui-dump" @(
            "shell", "rm", "-f", "/sdcard/motocom.xml"
        )
        Invoke-AdbCapture $serial "ui-dump" @(
            "shell", "uiautomator", "dump", "--compressed", "/sdcard/motocom.xml"
        )

        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $null = & $Adb -s $serial shell test -s "/sdcard/motocom.xml" 2>&1
            $uiDumpExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $uiDumpChecks += "attempt=$attempt exit=$uiDumpExitCode"
        if ($uiDumpExitCode -eq 0) {
            $uiDumpReady = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    Set-Content -Encoding utf8 -LiteralPath (
        Join-Path $out "$serial-ui-dump-check.txt"
    ) -Value $uiDumpChecks
    if (-not $uiDumpReady) {
        throw "ADB failed: $serial ui-dump"
    }

    $uiPath = Join-Path $out "$serial-ui.xml"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $pullOutput = & $Adb -s $serial pull "/sdcard/motocom.xml" $uiPath 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    Set-Content -Encoding utf8 -LiteralPath (
        Join-Path $out "$serial-ui-pull.txt"
    ) -Value @($pullOutput)
    if ($exitCode -ne 0) {
        throw "ADB failed: $serial ui-pull"
    }

    Invoke-AdbCapture $serial "motocom-logcat" @(
        "logcat", "-d", "-s",
        "MotoComP2P", "IntercomSignal", "RiderAudioEngine", "AudioRouteController"
    )
}

Write-Output "Device evidence: $((Resolve-Path $out).Path)"
