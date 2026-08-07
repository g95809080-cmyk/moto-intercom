[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Serial,
    [ValidateSet("normal", "slow", "offline", "online")]
    [string]$Mode,
    [ValidatePattern("^[A-Za-z0-9_.-]+$")]
    [string]$Interface = "",
    [string]$Adb = $(Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

$ErrorActionPreference = "Stop"
if ($Serial -notmatch "^emulator-\d+$") { throw "Only emulator serials are allowed" }

function Set-SharedInterfaceState {
    param([ValidateSet("up", "down")][string]$State)

    $rootOutput = & $Adb -s $Serial root 2>&1 | Out-String
    $rootExitCode = $LASTEXITCODE
    if (
        $rootExitCode -ne 0 -or
        $rootOutput -match "cannot run as root|production builds"
    ) {
        throw "ADB root is required to toggle shared interface $Interface on ${Serial}: $rootOutput"
    }

    & $Adb -s $Serial wait-for-device | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "ADB device did not return after root restart: $Serial" }

    & $Adb -s $Serial shell ip link set dev $Interface $State | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to set shared interface state: $Serial $Interface $State"
    }
}

switch ($Mode) {
    "normal" {
        & $Adb -s $Serial emu network speed full | Out-Null
        & $Adb -s $Serial emu network delay none | Out-Null
    }
    "slow" {
        & $Adb -s $Serial emu network speed edge | Out-Null
        & $Adb -s $Serial emu network delay gprs | Out-Null
    }
    "offline" {
        if ([string]::IsNullOrWhiteSpace($Interface) -or $Interface -eq "wlan0") {
            & $Adb -s $Serial shell svc wifi disable | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Unable to disable shared Wi-Fi network on $Serial" }
        } else {
            Set-SharedInterfaceState "down"
        }
    }
    "online" {
        if ([string]::IsNullOrWhiteSpace($Interface) -or $Interface -eq "wlan0") {
            & $Adb -s $Serial shell svc wifi enable | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Unable to restore shared Wi-Fi network on $Serial" }
        } else {
            Set-SharedInterfaceState "up"
        }
        & $Adb -s $Serial emu network speed full | Out-Null
        & $Adb -s $Serial emu network delay none | Out-Null
    }
}

[pscustomobject]@{
    serial = $Serial
    interface = $Interface
    mode = $Mode
    status = "APPLIED"
} |
    ConvertTo-Json
