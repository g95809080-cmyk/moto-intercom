[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Start", "Stop", "Check")]
    [string]$Mode,

    [ValidateSet(
        "lan-a-requester",
        "lan-b-requester",
        "p2p-a-requester",
        "p2p-b-requester",
        "restart-a",
        "restart-b",
        "disconnect-a",
        "disconnect-b",
        "background-a",
        "network-recovery"
    )]
    [string]$Scenario,

    [string]$RunDirectory,
    [Parameter(Mandatory = $true)]
    [string]$DeviceA,
    [Parameter(Mandatory = $true)]
    [string]$DeviceB,
    [string]$PackageName = "com.kuma.motointercom",
    [string]$Repository = "",
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$ApkPath = "M:\app\build\outputs\apk\debug\app-debug.apk",
    [ValidateSet("Pass", "Fail", "NotRun")]
    [string]$Result = "NotRun",
    [string]$Notes = "",
    [switch]$Relaunch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($Repository)) {
    $Repository = Split-Path -Parent $PSScriptRoot
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $AdbPath -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) {
        throw "adb failed for ${Serial}: $($Arguments -join ' ')`n$output"
    }
    return $output
}

function Assert-Device {
    param([string]$Serial)
    $state = (& $AdbPath -s $Serial get-state 2>$null).Trim()
    if ($state -ne "device") {
        throw "Device $Serial is not authorized and online (state=$state)"
    }
}

function Get-Prop {
    param([string]$Serial, [string]$Name)
    return ((Invoke-Adb $Serial @("shell", "getprop", $Name)) -join "`n").Trim()
}

function Get-InstalledIdentity {
    param([string]$Serial)
    $encoded = Invoke-Adb $Serial @(
        "shell", "run-as", $PackageName, "base64",
        "files/datastore/local_identity.preferences_pb"
    )
    $bytes = [Convert]::FromBase64String((($encoded -join "").Trim()))
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    $match = [regex]::Match(
        $text,
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    )
    if (-not $match.Success) { return "unavailable" }
    return $match.Value
}

function Get-InstalledApkHash {
    param([string]$Serial)
    $packagePath = ((Invoke-Adb $Serial @("shell", "pm", "path", $PackageName)) |
        Select-Object -First 1).Trim()
    if (-not $packagePath.StartsWith("package:")) {
        throw "Installed APK path unavailable for $Serial"
    }
    $devicePath = $packagePath.Substring("package:".Length)
    $hashLine = ((Invoke-Adb $Serial @("shell", "sha256sum", $devicePath)) |
        Select-Object -First 1).Trim()
    $match = [regex]::Match($hashLine, "^[0-9a-fA-F]{64}")
    if (-not $match.Success) { throw "Installed APK hash unavailable for $Serial" }
    return $match.Value.ToUpperInvariant()
}

function Write-DatabaseCheck {
    param([string]$Serial, [string]$Destination)
    $temporary = Join-Path $env:TEMP ("kum26-db-" + $Serial + "-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $temporary | Out-Null
    try {
        foreach ($name in @("pairings.db", "pairings.db-wal", "pairings.db-shm")) {
            $encoded = Invoke-Adb $Serial @(
                "shell", "run-as", $PackageName, "base64", "databases/$name"
            )
            [IO.File]::WriteAllBytes(
                (Join-Path $temporary $name),
                [Convert]::FromBase64String((($encoded -join "").Trim()))
            )
        }
        $python = @'
import sqlite3
import sys

path = sys.argv[1]
connection = sqlite3.connect(path)
try:
    print("integrity=" + connection.execute("pragma integrity_check").fetchone()[0])
    columns = [row[1] for row in connection.execute("pragma table_info(paired_peers)")]
    print("columns=" + ",".join(columns))
    for row in connection.execute(
        "select remoteDeviceId, lastTransport, failureCount "
        "from paired_peers order by remoteDeviceId"
    ):
        print("pairing=" + repr(row))
finally:
    connection.close()
'@
        $python | & python - (Join-Path $temporary "pairings.db") |
            Set-Content -Path $Destination -Encoding UTF8
        if ($LASTEXITCODE -ne 0) { throw "SQLite verification failed for $Serial" }
    } finally {
        Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Get-ScenarioInstructions {
    param([string]$Name)
    switch ($Name) {
        "lan-a-requester" { return "Use one LAN. A selects B; B responds; verify WebRTC; A disconnects." }
        "lan-b-requester" { return "Use one LAN. B selects A; A responds; verify WebRTC; B disconnects." }
        "p2p-a-requester" { return "Use Wi-Fi Direct. A selects B; B responds; verify WebRTC; A disconnects." }
        "p2p-b-requester" { return "Use Wi-Fi Direct. B selects A; A responds; verify WebRTC; B disconnects." }
        "restart-a" { return "Connect A to B, restart A process, then reconnect to the same B TargetLock." }
        "restart-b" { return "Connect A to B, restart B process, refresh Presence, then explicitly select B's new runtime." }
        "disconnect-a" { return "Connect A and B, then A requests DISCONNECT; verify both final states." }
        "disconnect-b" { return "Connect A and B, then B requests DISCONNECT; verify both final states." }
        "background-a" { return "Put A in background or lock it, keep B foreground, then exercise the expected confirmation path." }
        "network-recovery" { return "Connect A and B, interrupt only the planned transport, restore it, and verify recovery keeps the original target." }
        default { return "No scenario selected." }
    }
}

function Get-KeyLogLines {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    $pattern = @(
        "runtimeSessionId", "attemptId", "TargetLock", "Verified v2 control channel",
        "CONNECT_REQUEST", "CONNECT_ACCEPT", "CONNECT_REJECT", "BUSY", "DISCONNECT",
        "Starting WebRTC", "PeerConnection", "WebRTC", "timed out", "open failed",
        "channel", "Socket", "tunnel", "RECOVERING", "DISCOVERING"
    ) -join "|"
    return @(Select-String -Path $Path -Pattern $pattern -CaseSensitive:$false |
        ForEach-Object { $_.Line } | Select-Object -Last 250)
}

function New-Manifest {
    param([string]$Directory)
    $worktree = @(git -C $Repository status --porcelain)
    if ($worktree.Count -ne 0) {
        throw "Repository worktree must be clean before physical evidence capture"
    }
    if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK not found: $ApkPath" }
    $commit = (git -C $Repository rev-parse HEAD).Trim()
    $apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ApkPath).Hash
    $deviceAApkHash = Get-InstalledApkHash $DeviceA
    $deviceBApkHash = Get-InstalledApkHash $DeviceB
    if ($deviceAApkHash -ne $apkHash -or $deviceBApkHash -ne $apkHash) {
        throw "Installed APK hash does not match the local APK"
    }
    $lines = @(
        "evidence_type=two physical Android devices",
        "scenario=$Scenario",
        "started_at=$([DateTimeOffset]::Now.ToString('o'))",
        "commit_sha=$commit",
        "apk_path=$ApkPath",
        "apk_sha256=$apkHash",
        "device_a_apk_sha256=$deviceAApkHash",
        "device_b_apk_sha256=$deviceBApkHash",
        "device_a_serial=$DeviceA",
        "device_a_model=$(Get-Prop $DeviceA 'ro.product.model')",
        "device_a_android=$(Get-Prop $DeviceA 'ro.build.version.release')",
        "device_a_sdk=$(Get-Prop $DeviceA 'ro.build.version.sdk')",
        "device_a_identity=$(Get-InstalledIdentity $DeviceA)",
        "device_b_serial=$DeviceB",
        "device_b_model=$(Get-Prop $DeviceB 'ro.product.model')",
        "device_b_android=$(Get-Prop $DeviceB 'ro.build.version.release')",
        "device_b_sdk=$(Get-Prop $DeviceB 'ro.build.version.sdk')",
        "device_b_identity=$(Get-InstalledIdentity $DeviceB)",
        "deferred_physical_validation=three simultaneous physical Android devices in one LAN/P2P topology"
    )
    $lines | Set-Content -Path (Join-Path $Directory "manifest.txt") -Encoding UTF8
}

if (-not (Test-Path $AdbPath)) { throw "adb not found: $AdbPath" }
Assert-Device $DeviceA
Assert-Device $DeviceB

if ($Mode -eq "Check") {
    & $AdbPath devices -l
    Write-Output "A identity: $(Get-InstalledIdentity $DeviceA)"
    Write-Output "B identity: $(Get-InstalledIdentity $DeviceB)"
    exit 0
}

if ($Mode -eq "Start") {
    if ([string]::IsNullOrWhiteSpace($Scenario)) { throw "-Scenario is required for Start" }
    if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $RunDirectory = Join-Path $env:TEMP "motointercom-kum26\artifacts\$stamp-$Scenario"
    }
    New-Item -ItemType Directory -Force -Path $RunDirectory | Out-Null
    New-Manifest $RunDirectory
    (Get-ScenarioInstructions $Scenario) |
        Set-Content -Path (Join-Path $RunDirectory "scenario-instructions.txt") -Encoding UTF8
    "result=NotRun`nnotes=Capture started; Stop has not been called." |
        Set-Content -Path (Join-Path $RunDirectory "scenario-result.txt") -Encoding UTF8

    Invoke-Adb $DeviceA @("logcat", "-c") | Out-Null
    Invoke-Adb $DeviceB @("logcat", "-c") | Out-Null
    if ($Relaunch) {
        Invoke-Adb $DeviceA @("shell", "am", "force-stop", $PackageName) | Out-Null
        Invoke-Adb $DeviceB @("shell", "am", "force-stop", $PackageName) | Out-Null
        Invoke-Adb $DeviceA @("shell", "monkey", "-p", $PackageName, "1") | Out-Null
        Invoke-Adb $DeviceB @("shell", "monkey", "-p", $PackageName, "1") | Out-Null
    }
    Write-Output $RunDirectory
    exit 0
}

if ([string]::IsNullOrWhiteSpace($RunDirectory) -or -not (Test-Path $RunDirectory)) {
    throw "-RunDirectory must point to a Start capture directory"
}

$logcatFilter = @(
    "MotoIntercom:V", "MotoIntercomUi:V", "MotoComP2P:V", "IntercomSignal:V",
    "RiderAudioEngine:V", "AudioRouteController:V", "ModernAudioRoute:V", "WebRTC:V", "*:S"
)
$deviceALog = @(Invoke-Adb $DeviceA (@("logcat", "-d", "-v", "threadtime") + $logcatFilter))
$deviceBLog = @(Invoke-Adb $DeviceB (@("logcat", "-d", "-v", "threadtime") + $logcatFilter))
Set-Content -Path (Join-Path $RunDirectory "device-a.log") -Value $deviceALog -Encoding UTF8
Set-Content -Path (Join-Path $RunDirectory "device-b.log") -Value $deviceBLog -Encoding UTF8
Set-Content -Path (Join-Path $RunDirectory "device-a-state.txt") `
    -Value (Invoke-Adb $DeviceA @("shell", "dumpsys", "activity", "services", $PackageName)) `
    -Encoding UTF8
Set-Content -Path (Join-Path $RunDirectory "device-b-state.txt") `
    -Value (Invoke-Adb $DeviceB @("shell", "dumpsys", "activity", "services", $PackageName)) `
    -Encoding UTF8
$audioPattern = "MODE_IN_COMMUNICATION|Playback active|Recording active|USAGE_VOICE_COMMUNICATION|VOICE_COMMUNICATION|com.kuma.motointercom|Active Tracks|Tracks of which|Input thread"
$deviceAAudio = @(
    Invoke-Adb $DeviceA @("shell", "dumpsys", "audio")
    Invoke-Adb $DeviceA @("shell", "dumpsys", "media.audio_flinger")
) | Select-String -Pattern $audioPattern -CaseSensitive:$false |
    ForEach-Object { $_.Line }
$deviceBAudio = @(
    Invoke-Adb $DeviceB @("shell", "dumpsys", "audio")
    Invoke-Adb $DeviceB @("shell", "dumpsys", "media.audio_flinger")
) | Select-String -Pattern $audioPattern -CaseSensitive:$false |
    ForEach-Object { $_.Line }
Set-Content -Path (Join-Path $RunDirectory "device-a-audio.txt") -Value $deviceAAudio -Encoding UTF8
Set-Content -Path (Join-Path $RunDirectory "device-b-audio.txt") -Value $deviceBAudio -Encoding UTF8

Write-DatabaseCheck $DeviceA (Join-Path $RunDirectory "database-a-check.txt")
Write-DatabaseCheck $DeviceB (Join-Path $RunDirectory "database-b-check.txt")
Get-Content (Join-Path $RunDirectory "database-a-check.txt"),
    (Join-Path $RunDirectory "database-b-check.txt") |
    Set-Content -Path (Join-Path $RunDirectory "database-check.txt") -Encoding UTF8

$resultLines = @(
    "result=$Result",
    "stopped_at=$([DateTimeOffset]::Now.ToString('o'))",
    "notes=$Notes"
)
$resultLines | Set-Content -Path (Join-Path $RunDirectory "scenario-result.txt") -Encoding UTF8

$aKey = Get-KeyLogLines (Join-Path $RunDirectory "device-a.log")
$bKey = Get-KeyLogLines (Join-Path $RunDirectory "device-b.log")
$summary = @(
    "# KUM-26 Two-Device Scenario",
    "",
    "- Scenario: $Scenario",
    "- Result: $Result",
    "- Evidence type: two physical Android devices",
    "- Notes: $Notes",
    "- PC protocol endpoint is not part of this physical scenario.",
    "- Deferred physical validation: three simultaneous physical Android devices in one LAN/P2P topology.",
    "",
    "## Device A key lines",
    "",
    "~~~text",
    ($aKey -join "`n"),
    "~~~",
    "",
    "## Device B key lines",
    "",
    "~~~text",
    ($bKey -join "`n"),
    "~~~"
)
$summary | Set-Content -Path (Join-Path $RunDirectory "summary.md") -Encoding UTF8
Write-Output $RunDirectory
