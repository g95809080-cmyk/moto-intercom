[CmdletBinding()]
param(
    [ValidateSet("smoke", "nsd", "synthetic-audio", "audio-lifecycle", "recovery-timing", "recovery-reset", "active-disconnect", "sprint4-final", "network-fault", "restart", "all")]
    [string]$Scenario = "all",
    [string[]]$Serials = @(),
    [string]$Adb = $(Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [string]$TestPackage = "com.kuma.motointercom.instrumentation",
    [string]$ResultsRoot = "build\emulator-results"
)

$ErrorActionPreference = "Stop"
$targetPackage = "com.kuma.motointercom"
$runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($Serials.Count -eq 0) {
    $Serials = @(& $Adb devices | Select-String -Pattern "^emulator-\d+\s+device$" |
        ForEach-Object { ($_ -split "\s+")[0] })
}
if ($Serials.Count -eq 0) { throw "No ready emulator serials found" }
if ($Serials | Where-Object { $_ -notmatch "^emulator-\d+$" }) {
    throw "Only emulator serials are allowed"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$resultDir = Join-Path $ResultsRoot "$stamp-$Scenario"
New-Item -ItemType Directory -Force -Path $resultDir | Out-Null

function Invoke-AdbText {
    param([string]$Serial, [string]$Name, [string[]]$Arguments)
    $output = & $Adb -s $Serial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $path = Join-Path $resultDir "$Serial-$Name.txt"
    @($output) | Set-Content -LiteralPath $path -Encoding UTF8
    if ($exitCode -ne 0) { throw "ADB command failed: $Serial $Name" }
    return ($output -join [Environment]::NewLine)
}

function Assert-InstrumentationPassed {
    param([string]$Output, [string]$Label)
    if ($Output -match "FAILURES!!!" -or $Output -match "INSTRUMENTATION_FAILED" -or
        $Output -notmatch "OK \(") {
        throw "Instrumentation failed: $Label"
    }
}

function Stop-PairedServer {
    param(
        [System.Diagnostics.Process]$Process,
        [string]$Serial
    )
    if ($null -eq $Process -or $Process.HasExited) { return }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $Adb -s $Serial shell am force-stop $targetPackage 2>$null | Out-Null
        $null = $Process.WaitForExit(5000)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if (-not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force
    }
}

function Get-SharedAddress {
    param([string]$Serial)
    $output = Invoke-AdbText $Serial "shared-ip" @(
        "shell", "ip", "-o", "-4", "addr", "show", "wlan0"
    )
    $match = [regex]::Match($output, "inet\s+(?<address>\d+\.\d+\.\d+\.\d+)/")
    if (-not $match.Success) { throw "Shared network address missing: $Serial" }
    return $match.Groups["address"].Value
}

function Get-UiDump {
    param([string]$Serial)
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $output = Invoke-AdbText $Serial "ui" @(
            "exec-out", "uiautomator", "dump", "/dev/tty"
        )
        if ($output -match "<hierarchy\b" -and $output -notmatch "ERROR:") {
            return
        }
        if ($attempt -lt 3) { Start-Sleep -Seconds 1 }
    }
    throw "UI hierarchy was unavailable after 3 attempts: $Serial"
}

function Run-Smoke {
    foreach ($serial in $Serials) {
        Invoke-AdbText $serial "force-stop" @("shell", "am", "force-stop", $targetPackage) | Out-Null
        $activity = Invoke-AdbText $serial "resolve-activity" @(
            "shell", "cmd", "package", "resolve-activity", "--brief", $targetPackage
        )
        $component = ($activity -split "\r?\n" | Select-Object -Last 1).Trim()
        if ($component -notlike "$targetPackage/*") {
            throw "Launcher activity did not resolve: $serial output=$activity"
        }
        Invoke-AdbText $serial "launch" @("shell", "am", "start", "-W", "-n", $component) | Out-Null
        Get-UiDump $serial
    }

    $addresses = @{}
    foreach ($serial in $Serials) { $addresses[$serial] = Get-SharedAddress $serial }
    foreach ($source in $Serials) {
        foreach ($target in $Serials) {
            if ($source -eq $target) { continue }
            Invoke-AdbText $source "ping-$target" @(
                "shell", "ping", "-c", "1", "-W", "2", $addresses[$target]
            ) | Out-Null
        }
    }
}

function Run-SyntheticAudio {
    $local = Invoke-AdbText $Serials[0] "synthetic-metrics" @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "com.kuma.motointercom.SyntheticAudioMetricsTest",
        $runner
    )
    Assert-InstrumentationPassed $local "single-node synthetic metrics"

    if ($Serials.Count -lt 2) { throw "Synthetic network audio requires two emulators" }
    $server = $Serials[0]
    $client = $Serials[1]
    $serverIp = Get-SharedAddress $server
    $port = 39027
    $serverOut = Join-Path $resultDir "$server-synthetic-server.txt"
    $serverErr = Join-Path $resultDir "$server-synthetic-server.err.txt"
    $serverArgs = @(
        "-s", $server, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "com.kuma.motointercom.SyntheticAudioNetworkTest#exchange",
        "-e", "role", "server", "-e", "port", "$port", $runner
    )
    $serverProcess = Start-Process -FilePath $Adb -ArgumentList $serverArgs `
        -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr `
        -WindowStyle Hidden -PassThru
    try {
        Start-Sleep -Seconds 2
        $clientOutput = Invoke-AdbText $client "synthetic-client" @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.kuma.motointercom.SyntheticAudioNetworkTest#exchange",
            "-e", "role", "client", "-e", "host", $serverIp,
            "-e", "port", "$port", $runner
        )
        Assert-InstrumentationPassed $clientOutput "synthetic network client"

        if (-not $serverProcess.WaitForExit(60000)) {
            throw "Synthetic network server timed out"
        }
        $serverOutput = Get-Content -LiteralPath $serverOut -Raw -Encoding UTF8
        Assert-InstrumentationPassed $serverOutput "synthetic network server"
    } finally {
        Stop-PairedServer $serverProcess $server
    }
}

function Run-AudioLifecycle {
    $output = Invoke-AdbText $Serials[0] "audio-lifecycle" @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "com.kuma.motointercom.RiderAudioEngineHotSessionTest",
        $runner
    )
    Assert-InstrumentationPassed $output "hot audio session lifecycle"
}

function Run-RecoveryTiming {
    foreach ($serial in $Serials) {
        $output = Invoke-AdbText $serial "recovery-timing" @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.kuma.motointercom.RecoveryTimingInstrumentationTest",
            $runner
        )
        Assert-InstrumentationPassed $output "KUM-33 recovery timing on $serial"
    }
}

function Run-RecoveryReset {
    foreach ($serial in $Serials) {
        $output = Invoke-AdbText $serial "recovery-reset" @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.kuma.motointercom.RecoveryResetInstrumentationTest",
            $runner
        )
        Assert-InstrumentationPassed $output "KUM-34 recovery reset on $serial"
    }
}

function Run-ActiveDisconnect {
    foreach ($serial in $Serials) {
        $output = Invoke-AdbText $serial "active-disconnect" @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.kuma.motointercom.ActiveDisconnectInstrumentationTest",
            $runner
        )
        Assert-InstrumentationPassed $output "KUM-35 active disconnect on $serial"
    }
}

function Run-Sprint4Acceptance {
    foreach ($serial in $Serials) {
        $output = Invoke-AdbText $serial "sprint4-final" @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.kuma.motointercom.Sprint4FinalAcceptanceInstrumentationTest",
            $runner
        )
        Assert-InstrumentationPassed $output "KUM-36 Sprint 4 final acceptance on $serial"
    }
}

function Run-Sprint4Final {
    Run-Sprint4Acceptance
    Run-RecoveryTiming
    Run-RecoveryReset
    Run-ActiveDisconnect
    Run-SyntheticAudio
    Run-AudioLifecycle
    Run-NetworkFault
    Run-Restart
}

function Run-Nsd {
    if ($Serials.Count -lt 2) { throw "NSD integration requires two emulators" }
    $server = $Serials[0]
    $client = $Serials[1]
    $serverOut = Join-Path $resultDir "$server-nsd-server.txt"
    $serverErr = Join-Path $resultDir "$server-nsd-server.err.txt"
    $serverArgs = @(
        "-s", $server, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "com.kuma.motointercom.SharedNetworkNsdTest#exchange",
        "-e", "role", "server", $runner
    )
    $serverProcess = Start-Process -FilePath $Adb -ArgumentList $serverArgs `
        -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr `
        -WindowStyle Hidden -PassThru
    try {
        Start-Sleep -Seconds 3
        $clientOutput = Invoke-AdbText $client "nsd-client" @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.kuma.motointercom.SharedNetworkNsdTest#exchange",
            "-e", "role", "client", $runner
        )
        Assert-InstrumentationPassed $clientOutput "shared-network NSD client"

        if (-not $serverProcess.WaitForExit(60000)) {
            throw "Shared-network NSD server timed out"
        }
        $serverOutput = Get-Content -LiteralPath $serverOut -Raw -Encoding UTF8
        Assert-InstrumentationPassed $serverOutput "shared-network NSD server"
    } finally {
        Stop-PairedServer $serverProcess $server
    }
}

function Run-Restart {
    foreach ($serial in $Serials) {
        Invoke-AdbText $serial "restart-stop" @("shell", "am", "force-stop", $targetPackage) | Out-Null
        $activity = Invoke-AdbText $serial "restart-resolve" @(
            "shell", "cmd", "package", "resolve-activity", "--brief", $targetPackage
        )
        $component = ($activity -split "\r?\n" | Select-Object -Last 1).Trim()
        Invoke-AdbText $serial "restart-launch" @("shell", "am", "start", "-W", "-n", $component) | Out-Null
        $appProcessId = Invoke-AdbText $serial "restart-pid" @(
            "shell", "pidof", "-s", $targetPackage
        )
        if ([string]::IsNullOrWhiteSpace($appProcessId)) {
            throw "App did not restart: $serial"
        }
    }
}

function Run-NetworkFault {
    if ($Serials.Count -lt 2) { throw "Network fault recovery requires two emulators" }
    $source = $Serials[0]
    $target = $Serials[1]
    $targetIp = Get-SharedAddress $target
    $faultScript = Join-Path $scriptRoot "inject-network-fault.ps1"

    & $faultScript -Serial $target -Mode offline -Adb $Adb |
        Set-Content -LiteralPath (Join-Path $resultDir "$target-fault-offline.json") -Encoding UTF8
    try {
        Start-Sleep -Seconds 2
        $offlineOutput = & $Adb -s $source shell ping -c 1 -W 2 $targetIp 2>&1
        @($offlineOutput) | Set-Content -LiteralPath (
            Join-Path $resultDir "$source-offline-ping-$target.txt"
        ) -Encoding UTF8
        if ($LASTEXITCODE -eq 0) { throw "Shared network stayed reachable after offline fault" }
    } finally {
        & $faultScript -Serial $target -Mode online -Adb $Adb |
            Set-Content -LiteralPath (Join-Path $resultDir "$target-fault-online.json") -Encoding UTF8
    }

    $deadline = (Get-Date).AddSeconds(30)
    $recovered = $false
    do {
        Start-Sleep -Seconds 2
        $onlineOutput = & $Adb -s $source shell ping -c 1 -W 2 $targetIp 2>&1
        $recovered = $LASTEXITCODE -eq 0
    } while (-not $recovered -and (Get-Date) -lt $deadline)
    @($onlineOutput) | Set-Content -LiteralPath (
        Join-Path $resultDir "$source-recovered-ping-$target.txt"
    ) -Encoding UTF8
    if (-not $recovered) { throw "Shared network did not recover within 30 seconds" }
}

switch ($Scenario) {
    "smoke" { Run-Smoke }
    "nsd" { Run-Nsd }
    "synthetic-audio" { Run-SyntheticAudio }
    "audio-lifecycle" { Run-AudioLifecycle }
    "recovery-timing" { Run-RecoveryTiming }
    "recovery-reset" { Run-RecoveryReset }
    "active-disconnect" { Run-ActiveDisconnect }
    "sprint4-final" { Run-Sprint4Final }
    "network-fault" { Run-NetworkFault }
    "restart" { Run-Restart }
    "all" {
        Run-Smoke
        Run-Nsd
        Run-Sprint4Final
    }
}

[pscustomobject]@{
    scenario = $Scenario
    serials = $Serials
    resultDirectory = (Resolve-Path -LiteralPath $resultDir).Path
    status = "PASS"
} | ConvertTo-Json -Depth 4
