[CmdletBinding()]
param(
    [ValidateRange(1, 3)]
    [int]$Count = 3,
    [string]$SdkRoot = $(
        if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
        else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    ),
    [string]$AvdName = "MotoIntercom_API_36",
    [string]$SystemImage = "system-images;android-36;aosp_atd;x86_64",
    [ValidateRange(5554, 5584)]
    [int]$FirstPort = 5554,
    [ValidateRange(2, 250)]
    [int]$SharedNetBase = 10,
    [ValidateRange(30, 900)]
    [int]$BootTimeoutSeconds = 300,
    [ValidateRange(1024, 4096)]
    [int]$MemoryMb = 1536,
    [ValidateRange(1, 4)]
    [int]$Cores = 2,
    [ValidateRange(0, 30)]
    [int]$LaunchStaggerSeconds = 8,
    [switch]$Windowed
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
$check = Join-Path $scriptRoot "check-version.ps1"
$environment = & $check -SdkRoot $SdkRoot -AvdName $AvdName `
    -SystemImage $SystemImage -RequireSystemImage | ConvertFrom-Json
$adb = $environment.adb
$emulator = $environment.emulator
$avdManager = $environment.avdManager
$stateDir = Join-Path $repoRoot "build\emulator-cluster"
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null

if (-not $environment.avdInstalled) {
    "no" | & $avdManager create avd --name $AvdName `
        --package $SystemImage --device "pixel_6"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create AVD $AvdName"
    }
}

$nodes = @()
for ($index = 0; $index -lt $Count; $index++) {
    $port = $FirstPort + ($index * 2)
    if ($port -gt 5584) { throw "Emulator port exceeds 5584: $port" }
    $serial = "emulator-$port"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $existing = (& $adb -s $serial get-state 2>&1 | Out-String).Trim()
        $existingExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($existingExitCode -eq 0 -and $existing -eq "device") {
        throw "ADB serial is already occupied: $serial"
    }

    $arguments = @(
        "-avd", $AvdName,
        "-port", "$port",
        "-read-only",
        "-no-snapshot",
        "-no-boot-anim",
        "-gpu", "swiftshader_indirect",
        "-memory", "$MemoryMb",
        "-cores", "$Cores",
        "-no-audio",
        "-netdelay", "none",
        "-netspeed", "full",
        "-shared-net-id", "$($SharedNetBase + $index)",
        "-no-metrics"
    )
    if (-not $Windowed) { $arguments += "-no-window" }

    $startArgs = @{
        FilePath = $emulator
        ArgumentList = $arguments
        PassThru = $true
        RedirectStandardOutput = Join-Path $stateDir "$serial-emulator.out.txt"
        RedirectStandardError = Join-Path $stateDir "$serial-emulator.err.txt"
    }
    if (-not $Windowed) { $startArgs.WindowStyle = "Hidden" }
    $process = Start-Process @startArgs
    $nodes += [pscustomobject]@{
        index = $index
        serial = $serial
        port = $port
        sharedNetId = $SharedNetBase + $index
        processId = $process.Id
    }
    if ($LaunchStaggerSeconds -gt 0 -and $index -lt $Count - 1) {
        Start-Sleep -Seconds $LaunchStaggerSeconds
    }
}

$statePath = Join-Path $stateDir "cluster.json"
[pscustomobject]@{
    avdName = $AvdName
    startedAt = (Get-Date).ToString("o")
    nodes = $nodes
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8

foreach ($node in $nodes) {
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $ready = $false
    do {
        Start-Sleep -Seconds 2
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $state = (& $adb -s $node.serial get-state 2>&1 | Out-String).Trim()
            $stateExitCode = $LASTEXITCODE
            if ($stateExitCode -eq 0 -and $state -eq "device") {
                $booted = (& $adb -s $node.serial shell getprop sys.boot_completed 2>&1 |
                    Out-String).Trim()
                $bootExitCode = $LASTEXITCODE
                $pmReady = (& $adb -s $node.serial shell pm path android 2>&1 |
                    Out-String).Trim()
                $pmExitCode = $LASTEXITCODE
                $ready = $bootExitCode -eq 0 -and $pmExitCode -eq 0 -and
                    $booted -eq "1" -and $pmReady -like "package:*"
            }
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    } while (-not $ready -and (Get-Date) -lt $deadline)

    if (-not $ready) {
        throw "Emulator failed to boot within $BootTimeoutSeconds seconds: $($node.serial)"
    }

    & $adb -s $node.serial shell settings put global window_animation_scale 0 | Out-Null
    & $adb -s $node.serial shell settings put global transition_animation_scale 0 | Out-Null
    & $adb -s $node.serial shell settings put global animator_duration_scale 0 | Out-Null
}

[pscustomobject]@{
    state = (Resolve-Path -LiteralPath $statePath).Path
    avdName = $AvdName
    nodes = $nodes
} | ConvertTo-Json -Depth 5
