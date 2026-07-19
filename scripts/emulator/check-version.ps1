[CmdletBinding()]
param(
    [string]$SdkRoot = $(
        if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
        else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    ),
    [version]$MinimumEmulatorVersion = [version]"36.5.0.0",
    [string]$AvdName = "MotoIntercom_API_36",
    [string]$SystemImage = "system-images;android-36;aosp_atd;x86_64",
    [switch]$RequireSystemImage,
    [switch]$RequireAvd
)

$ErrorActionPreference = "Stop"

function Require-File {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label not found: $Path"
    }
    return $Path
}

$emulator = Require-File (Join-Path $SdkRoot "emulator\emulator.exe") "Emulator"
$adb = Require-File (Join-Path $SdkRoot "platform-tools\adb.exe") "ADB"
$sdkManager = Require-File (
    Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
) "sdkmanager"
$avdManager = Require-File (
    Join-Path $SdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
) "avdmanager"

$versionLine = (& $emulator -version 2>&1 | Select-Object -First 1 | Out-String).Trim()
if ($versionLine -notmatch "(?<version>\d+\.\d+\.\d+(?:\.\d+)?)") {
    throw "Unable to parse emulator version: $versionLine"
}
$currentVersion = [version]$Matches.version
if ($currentVersion -lt $MinimumEmulatorVersion) {
    throw "Emulator $currentVersion is older than required $MinimumEmulatorVersion"
}

$parts = $SystemImage.Split(";")
if ($parts.Count -ne 4 -or $parts[0] -ne "system-images") {
    throw "Unsupported system image package: $SystemImage"
}
$imagePath = Join-Path $SdkRoot (
    "system-images\{0}\{1}\{2}" -f $parts[1], $parts[2], $parts[3]
)
$imageInstalled = Test-Path -LiteralPath $imagePath -PathType Container
$avds = @(& $emulator -list-avds 2>$null)
$avdInstalled = $avds -contains $AvdName

if ($RequireSystemImage -and -not $imageInstalled) {
    throw "System image is not installed: $SystemImage"
}
if ($RequireAvd -and -not $avdInstalled) {
    throw "AVD is not installed: $AvdName"
}

[pscustomobject]@{
    sdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
    emulator = $emulator
    adb = $adb
    sdkManager = $sdkManager
    avdManager = $avdManager
    emulatorVersion = $currentVersion.ToString()
    minimumVersion = $MinimumEmulatorVersion.ToString()
    systemImage = $SystemImage
    systemImageInstalled = $imageInstalled
    avdName = $AvdName
    avdInstalled = $avdInstalled
} | ConvertTo-Json -Depth 3
