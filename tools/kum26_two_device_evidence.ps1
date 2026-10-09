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
        [AllowEmptyString()][string]$Serial,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $prefix = if ($Serial) { @("-s", $Serial) } else { @() }
        $output = & $AdbPath @prefix @Arguments 2>&1
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
    $state = ((Invoke-Adb $Serial @("get-state")) -join "`n").Trim()
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
    # DataStore PreferencesProto: map field 1, entry key 1/value 2, string value 5.
    # A UUID in nickname is not the installation identity.
    $decoder = @'
import base64
import sys
import uuid

def varint(data, position):
    value = 0
    for shift in range(0, 70, 7):
        if position >= len(data):
            raise ValueError("truncated protobuf varint")
        byte = data[position]
        position += 1
        if shift == 63 and byte > 1:
            raise ValueError("oversized protobuf varint")
        value |= (byte & 127) << shift
        if byte < 128:
            return value, position
    raise ValueError("oversized protobuf varint")

def fields(data):
    position = 0
    while position < len(data):
        tag, position = varint(data, position)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("invalid protobuf field")
        if wire == 0:
            value, position = varint(data, position)
        elif wire in (1, 2, 5):
            if wire == 2:
                size, position = varint(data, position)
            else:
                size = 8 if wire == 1 else 4
            end = position + size
            if end > len(data):
                raise ValueError("truncated protobuf field")
            value, position = data[position:end], end
        else:
            raise ValueError("unsupported protobuf wire type")
        yield number, wire, value

identities = []
for number, wire, entry in fields(base64.b64decode(sys.argv[1], validate=True)):
    if number != 1:
        continue
    if wire != 2:
        raise ValueError("invalid preference entry")
    parts = list(fields(entry))
    keys = [value for n, w, value in parts if (n, w) == (1, 2)]
    values = [value for n, w, value in parts if (n, w) == (2, 2)]
    if len(keys) != 1 or len(values) != 1 or len(parts) != 2:
        raise ValueError("ambiguous preference entry")
    if keys[0].decode("utf-8") == "device_id":
        content = list(fields(values[0]))
        if len(content) != 1 or content[0][:2] != (5, 2):
            raise ValueError("device_id must be a string preference")
        identity = content[0][2].decode("utf-8")
        if str(uuid.UUID(identity)) != identity:
            raise ValueError("device_id must be a canonical UUID")
        identities.append(identity)
if len(identities) != 1:
    raise ValueError("exactly one device_id preference is required")
print(identities[0])
'@
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $decoded = @($decoder | & python - (($encoded -join "").Trim()) 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($exitCode -ne 0 -or $decoded.Count -ne 1 -or
        [string]$decoded[0] -cnotmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {
        throw "Installed device_id unavailable for ${Serial}: $($decoded -join ' ')"
    }
    return [string]$decoded[0]
}

function Get-PhysicalPair {
    $devices = @()
    foreach ($serial in @($DeviceA, $DeviceB)) {
        if ($serial -match '^emulator-\d+$') { throw "Emulator serial is not physical evidence: $serial" }
        Assert-Device $serial
        $model = Get-Prop $serial 'ro.product.model'
        $release = Get-Prop $serial 'ro.build.version.release'
        $sdk = Get-Prop $serial 'ro.build.version.sdk'
        if (-not $model -or -not $release -or $sdk -notmatch '^[1-9][0-9]*$') {
            throw "Required device metadata unavailable for $serial"
        }
        $kernelQemu = Get-Prop $serial 'ro.kernel.qemu'
        $bootQemu = Get-Prop $serial 'ro.boot.qemu'
        foreach ($marker in @($kernelQemu, $bootQemu)) {
            if ($marker -ne '' -and $marker -ne '0') {
                throw "Emulator or unknown qemu marker for $serial"
            }
        }
        $devices += [pscustomobject]@{
            serial=$serial; model=$model; android=$release; sdk=$sdk
            kernel_qemu=$kernelQemu; boot_qemu=$bootQemu
            identity=(Get-InstalledIdentity $serial)
        }
    }
    if ($devices[0].identity -ceq $devices[1].identity) {
        throw "A and B must have different installation device_id values"
    }
    return $devices
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
    $tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $tempLeaf = "kum26-db-" + [guid]::NewGuid().ToString("N")
    $temporary = [IO.Path]::GetFullPath((Join-Path $tempBase $tempLeaf))
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
    integrity = connection.execute("pragma integrity_check").fetchall()
    if integrity != [("ok",)]:
        raise ValueError("SQLite integrity check failed: " + repr(integrity))
    print("integrity=ok")
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
        $cleanup = [IO.DirectoryInfo]$temporary
        if ($cleanup.Parent.FullName.TrimEnd([IO.Path]::DirectorySeparatorChar) -ne $tempBase -or
            $cleanup.Name -ne $tempLeaf) { throw "Refusing cleanup outside owned database scratch directory" }
        Remove-Item -LiteralPath $temporary -Recurse -Force
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

function Get-ManifestValue {
    param([string]$Path, [string]$Name)
    $prefix = "$Name="
    $line = Get-Content -LiteralPath $Path -Encoding UTF8 |
        Where-Object { $_.StartsWith($prefix) }
    if (@($line).Count -ne 1 -or $line.Length -eq $prefix.Length) {
        throw "Manifest value missing or ambiguous: $Name"
    }
    return $line.Substring($prefix.Length)
}

function Invoke-Git {
    param([string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(git -C $Repository @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($exitCode -ne 0) { throw "Git source verification failed: $($Arguments -join ' ')" }
    return $output
}

function New-Manifest {
    param([string]$Directory, [object[]]$Devices)
    $worktree = @(Invoke-Git @("status", "--porcelain"))
    if ($worktree.Count -ne 0) {
        throw "Repository worktree must be clean before physical evidence capture"
    }
    if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK not found: $ApkPath" }
    $commit = ((Invoke-Git @("rev-parse", "HEAD")) -join "`n").Trim()
    if ($commit -notmatch '^[0-9a-f]{40}$') { throw "Git commit SHA unavailable" }
    $apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ApkPath).Hash
    $deviceAApkHash = Get-InstalledApkHash $DeviceA
    $deviceBApkHash = Get-InstalledApkHash $DeviceB
    if ($deviceAApkHash -ne $apkHash -or $deviceBApkHash -ne $apkHash) {
        throw "Installed APK hash does not match the local APK"
    }
    $lines = @(
        "capture_format=2",
        "evidence_type=two physical Android devices",
        "scenario=$Scenario",
        "started_at=$([DateTimeOffset]::Now.ToString('o'))",
        "commit_sha=$commit",
        "apk_path=$ApkPath",
        "apk_sha256=$apkHash",
        "device_a_apk_sha256=$deviceAApkHash",
        "device_b_apk_sha256=$deviceBApkHash",
        "device_a_serial=$DeviceA",
        "device_a_model=$($Devices[0].model)",
        "device_a_android=$($Devices[0].android)",
        "device_a_sdk=$($Devices[0].sdk)",
        "device_a_kernel_qemu=$($Devices[0].kernel_qemu)",
        "device_a_boot_qemu=$($Devices[0].boot_qemu)",
        "device_a_identity=$($Devices[0].identity)",
        "device_b_serial=$DeviceB",
        "device_b_model=$($Devices[1].model)",
        "device_b_android=$($Devices[1].android)",
        "device_b_sdk=$($Devices[1].sdk)",
        "device_b_kernel_qemu=$($Devices[1].kernel_qemu)",
        "device_b_boot_qemu=$($Devices[1].boot_qemu)",
        "device_b_identity=$($Devices[1].identity)",
        "deferred_physical_validation=three simultaneous physical Android devices in one LAN/P2P topology"
    )
    $lines | Set-Content -Path (Join-Path $Directory "manifest.txt") -Encoding UTF8
}

function Set-CurrentResult {
    param([string[]]$Lines)
    # One atomic status file is authoritative; summaries in captures are historical.
    $target = Join-Path $RunDirectory "scenario-result.txt"
    $temporary = Join-Path $RunDirectory ([guid]::NewGuid().ToString("N") + ".tmp")
    try {
        [IO.File]::WriteAllText($temporary, ($Lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
        for ($attempt = 0; ; $attempt++) {
            try {
                if ([IO.File]::Exists($target)) { [IO.File]::Replace($temporary, $target, [NullString]::Value) }
                else { [IO.File]::Move($temporary, $target) }
                break
            } catch {
                $nativeError = $_.Exception.GetBaseException().HResult -band 0xffff
                if ($attempt -ge 4 -or $nativeError -notin @(32, 33, 1175)) { throw }
                # Bounded sharing-violation retry; never delete the current file as a fallback.
                Start-Sleep -Milliseconds 50
            }
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { Remove-Item -LiteralPath $temporary -Force }
    }
}

if ($Mode -eq "Start") {
    if ([string]::IsNullOrWhiteSpace($Scenario)) { throw "-Scenario is required for Start" }
    if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
        $RunDirectory = Join-Path $env:TEMP ("motointercom-kum26\artifacts\" + [guid]::NewGuid())
    }
    if (Test-Path -LiteralPath $RunDirectory) {
        if (@(Get-ChildItem -LiteralPath $RunDirectory -Force).Count -ne 0) {
            throw "Start requires a new or empty directory; previous evidence cannot be overwritten"
        }
    } else { New-Item -ItemType Directory -Path $RunDirectory | Out-Null }
} elseif ($Mode -eq "Stop") {
    if ([string]::IsNullOrWhiteSpace($RunDirectory) -or
        -not (Test-Path -LiteralPath $RunDirectory -PathType Container)) {
        throw "-RunDirectory must point to a Start capture directory"
    }
}

$captureId = [guid]::NewGuid().ToString("N")
$captureDirectory = $null
# Invalidate the current decision before any external command, including get-state.
if ($Mode -ne "Check") {
    Set-CurrentResult @("result=NotRun", "capture_status=collecting", "capture_id=$captureId")
}

try {
    if ($Mode -ne "Check") {
        @("# KUM-26 capture index", "", "Current result: scenario-result.txt.",
          "Each captures/<capture_id>/ directory is a separate historical snapshot.",
          "A failed capture is NotRun; an older snapshot does not describe the current attempt.") |
            Set-Content -LiteralPath (Join-Path $RunDirectory "summary.md") -Encoding UTF8
    }
    $DeviceA = $DeviceA.Trim()
    $DeviceB = $DeviceB.Trim()
    if (-not $DeviceA -or -not $DeviceB -or $DeviceA -ceq $DeviceB) {
        throw "A and B must use different nonempty serials"
    }
    if (-not (Test-Path -LiteralPath $AdbPath)) { throw "adb not found: $AdbPath" }
    $devices = @(Get-PhysicalPair)
    Invoke-Adb '' @("devices", "-l") | Out-Null
    if ($Mode -eq "Check") {
        Write-Output "A identity: $($devices[0].identity)"
        Write-Output "B identity: $($devices[1].identity)"
        exit 0
    }
    if ($Mode -eq "Start") {
        New-Manifest $RunDirectory $devices
        Write-DatabaseCheck $DeviceA (Join-Path $RunDirectory "database-a-before.txt")
        Write-DatabaseCheck $DeviceB (Join-Path $RunDirectory "database-b-before.txt")
        Get-Content (Join-Path $RunDirectory "database-a-before.txt"),
            (Join-Path $RunDirectory "database-b-before.txt") |
            Set-Content -LiteralPath (Join-Path $RunDirectory "database-before-check.txt") -Encoding UTF8
        (Get-ScenarioInstructions $Scenario) |
            Set-Content -LiteralPath (Join-Path $RunDirectory "scenario-instructions.txt") -Encoding UTF8
        Invoke-Adb $DeviceA @("logcat", "-c") | Out-Null
        Invoke-Adb $DeviceB @("logcat", "-c") | Out-Null
        if ($Relaunch) {
            Invoke-Adb $DeviceA @("shell", "am", "force-stop", $PackageName) | Out-Null
            Invoke-Adb $DeviceB @("shell", "am", "force-stop", $PackageName) | Out-Null
            Invoke-Adb $DeviceA @("shell", "monkey", "-p", $PackageName, "1") | Out-Null
            Invoke-Adb $DeviceB @("shell", "monkey", "-p", $PackageName, "1") | Out-Null
        }
        Set-CurrentResult @("result=NotRun", "capture_status=ready", "capture_id=$captureId",
            "notes=Capture started; Stop has not been called.")
        Write-Output $RunDirectory
        exit 0
    }

    $manifestPath = Join-Path $RunDirectory "manifest.txt"
    if ((Get-ManifestValue $manifestPath "capture_format") -ne '2') {
        throw "Start a new capture with this script before Stop"
    }
    $originalScenario = Get-ManifestValue $manifestPath "scenario"
    if ($Scenario -and $Scenario -ne $originalScenario) { throw "Scenario changed since Start" }
    $Scenario = $originalScenario
    $expectedA = Get-ManifestValue $manifestPath "device_a_identity"
    $expectedB = Get-ManifestValue $manifestPath "device_b_identity"
    if ((Get-ManifestValue $manifestPath "device_a_serial") -ceq
        (Get-ManifestValue $manifestPath "device_b_serial")) { throw "Start manifest requires different serials" }
    if ($expectedA -ceq $expectedB -or $devices[0].identity -cne $expectedA -or
        $devices[1].identity -cne $expectedB) { throw "Installed identity changed during the evidence scenario" }
    $expectedApkHash = Get-ManifestValue $manifestPath "apk_sha256"
    $hashA = Get-InstalledApkHash $DeviceA
    $hashB = Get-InstalledApkHash $DeviceB
    if ($hashA -cne $expectedApkHash -or $hashB -cne $expectedApkHash) {
        throw "Installed APK changed during the evidence scenario"
    }

    $captureDirectory = Join-Path $RunDirectory "captures/$captureId"
    New-Item -ItemType Directory -Path $captureDirectory | Out-Null
    $logcatFilter = @("MotoIntercom:V", "MotoIntercomUi:V", "MotoComP2P:V", "IntercomSignal:V",
        "RiderAudioEngine:V", "AudioRouteController:V", "ModernAudioRoute:V", "WebRTC:V", "*:S")
    $audioPattern = "MODE_IN_COMMUNICATION|Playback active|Recording active|USAGE_VOICE_COMMUNICATION|VOICE_COMMUNICATION|com.kuma.motointercom|Active Tracks|Tracks of which|Input thread"
    foreach ($label in @("a", "b")) {
        $serial = if ($label -eq 'a') { $DeviceA } else { $DeviceB }
        Invoke-Adb $serial (@("logcat", "-d", "-v", "threadtime") + $logcatFilter) |
            Set-Content -LiteralPath (Join-Path $captureDirectory "device-$label.log") -Encoding UTF8
        Invoke-Adb $serial @("shell", "dumpsys", "activity", "services", $PackageName) |
            Set-Content -LiteralPath (Join-Path $captureDirectory "device-$label-state.txt") -Encoding UTF8
        @(
            Invoke-Adb $serial @("shell", "dumpsys", "audio")
            Invoke-Adb $serial @("shell", "dumpsys", "media.audio_flinger")
        ) | Select-String -Pattern $audioPattern -CaseSensitive:$false |
            ForEach-Object { $_.Line } |
            Set-Content -LiteralPath (Join-Path $captureDirectory "device-$label-audio.txt") -Encoding UTF8
        Write-DatabaseCheck $serial (Join-Path $captureDirectory "database-$label-check.txt")
    }
    Get-Content (Join-Path $captureDirectory "database-a-check.txt"),
        (Join-Path $captureDirectory "database-b-check.txt") |
        Set-Content -LiteralPath (Join-Path $captureDirectory "database-check.txt") -Encoding UTF8
    # Re-check after collection as well: a reinstall or device replacement must not pass.
    $devices = @(Get-PhysicalPair)
    $hashA = Get-InstalledApkHash $DeviceA
    $hashB = Get-InstalledApkHash $DeviceB
    if ($devices[0].identity -cne $expectedA -or $devices[1].identity -cne $expectedB -or
        $hashA -cne $expectedApkHash -or $hashB -cne $expectedApkHash) {
        throw "Installed identity or APK changed during capture"
    }
    $aKey = Get-KeyLogLines (Join-Path $captureDirectory "device-a.log")
    $bKey = Get-KeyLogLines (Join-Path $captureDirectory "device-b.log")
    $resultLines = @("result=$Result", "capture_status=complete", "capture_id=$captureId",
        "capture_directory=captures/$captureId", "stopped_at=$([DateTimeOffset]::Now.ToString('o'))",
        "device_a_serial_after=$DeviceA", "device_b_serial_after=$DeviceB",
        "device_a_model_after=$($devices[0].model)", "device_b_model_after=$($devices[1].model)",
        "device_a_android_after=$($devices[0].android)", "device_b_android_after=$($devices[1].android)",
        "device_a_sdk_after=$($devices[0].sdk)", "device_b_sdk_after=$($devices[1].sdk)",
        "device_a_kernel_qemu_after=$($devices[0].kernel_qemu)", "device_a_boot_qemu_after=$($devices[0].boot_qemu)",
        "device_b_kernel_qemu_after=$($devices[1].kernel_qemu)", "device_b_boot_qemu_after=$($devices[1].boot_qemu)",
        "device_a_apk_sha256_after=$hashA", "device_b_apk_sha256_after=$hashB", "apk_match=true",
        "device_a_identity_after=$($devices[0].identity)", "device_b_identity_after=$($devices[1].identity)",
        "identity_match=true", "notes=$Notes")
    @("# KUM-26 Two-Device Scenario", "", "- Scenario: $Scenario", "- Result: $Result",
        "- Evidence type: two physical Android devices", "- Capture: $captureId", "- Notes: $Notes",
        "- PC protocol endpoint is not part of this physical scenario.",
        "- Deferred physical validation: three simultaneous physical Android devices in one LAN/P2P topology.",
        "", "## Device A key lines", "", "~~~text", ($aKey -join "`n"), "~~~",
        "", "## Device B key lines", "", "~~~text", ($bKey -join "`n"), "~~~") |
        Set-Content -LiteralPath (Join-Path $captureDirectory "summary.md") -Encoding UTF8
    $resultLines | Set-Content -LiteralPath (Join-Path $captureDirectory "scenario-result.txt") -Encoding UTF8
    # Publish only after every capture, validation and summary write has completed.
    Set-CurrentResult $resultLines
    Write-Output $captureDirectory
} catch {
    if ($Mode -ne "Check") {
        $failedLines = @("result=NotRun", "capture_status=capture_failed", "capture_id=$captureId",
            "notes=Capture failed; no current product verdict is available.")
        Set-CurrentResult $failedLines
        if ($captureDirectory -and (Test-Path -LiteralPath $captureDirectory)) {
            $failedLines | Set-Content -LiteralPath (Join-Path $captureDirectory "scenario-result.txt") -Encoding UTF8
            "# Incomplete capture`nNo product verdict is available for this attempt." |
                Set-Content -LiteralPath (Join-Path $captureDirectory "summary.md") -Encoding UTF8
        }
    }
    throw
}
