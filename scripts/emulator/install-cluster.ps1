[CmdletBinding()]
param(
    [string[]]$Serials = @(),
    [string]$Adb = $(Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$TestApk = "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw "ADB not found: $Adb" }
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
if (-not (Test-Path -LiteralPath $TestApk -PathType Leaf)) {
    throw "Android test APK not found: $TestApk"
}

if ($Serials.Count -eq 0) {
    $Serials = @(& $Adb devices | Select-String -Pattern "^emulator-\d+\s+device$" |
        ForEach-Object { ($_ -split "\s+")[0] })
}
if ($Serials.Count -eq 0) { throw "No ready emulator serials found" }
if ($Serials | Where-Object { $_ -notmatch "^emulator-\d+$" }) {
    throw "Only explicit emulator serials are allowed: $($Serials -join ', ')"
}

$permissions = @(
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.NEARBY_WIFI_DEVICES",
    "android.permission.RECORD_AUDIO",
    "android.permission.BLUETOOTH_CONNECT"
)
$installed = @()
foreach ($serial in $Serials) {
    $state = (& $Adb -s $serial get-state 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $state -ne "device") {
        throw "Emulator is not ready: $serial state=$state"
    }
    & $Adb -s $serial install -r $Apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "APK install failed: $serial" }
    & $Adb -s $serial install -r -t $TestApk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Android test APK install failed: $serial" }
    foreach ($permission in $permissions) {
        & $Adb -s $serial shell pm grant com.kuma.motointercom $permission `
            2>$null | Out-Null
    }
    & $Adb -s $serial shell am force-stop com.kuma.motointercom | Out-Null
    $installed += [pscustomobject]@{
        serial = $serial
        apk = (Resolve-Path $Apk).Path
        testApk = (Resolve-Path $TestApk).Path
    }
}

$installed | ConvertTo-Json -Depth 3
