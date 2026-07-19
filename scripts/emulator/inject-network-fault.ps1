[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Serial,
    [ValidateSet("normal", "slow", "offline", "online")]
    [string]$Mode,
    [string]$Adb = $(Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

$ErrorActionPreference = "Stop"
if ($Serial -notmatch "^emulator-\d+$") { throw "Only emulator serials are allowed" }

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
        & $Adb -s $Serial shell svc wifi disable | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to disable shared network on $Serial" }
    }
    "online" {
        & $Adb -s $Serial shell svc wifi enable | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to restore shared network on $Serial" }
        & $Adb -s $Serial emu network speed full | Out-Null
        & $Adb -s $Serial emu network delay none | Out-Null
    }
}

[pscustomobject]@{ serial = $Serial; mode = $Mode; status = "APPLIED" } |
    ConvertTo-Json
