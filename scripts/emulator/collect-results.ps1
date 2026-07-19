[CmdletBinding()]
param(
    [string[]]$Serials = @(),
    [string]$Adb = $(Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [string]$OutputRoot = "build\emulator-evidence"
)

$ErrorActionPreference = "Stop"
if ($Serials.Count -eq 0) {
    $Serials = @(& $Adb devices | Select-String -Pattern "^emulator-\d+\s+device$" |
        ForEach-Object { ($_ -split "\s+")[0] })
}
if ($Serials.Count -eq 0) { throw "No emulator serials found" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$output = Join-Path $OutputRoot $stamp
New-Item -ItemType Directory -Force -Path $output | Out-Null

function Capture-Text {
    param([string]$Serial, [string]$Name, [string[]]$Arguments)
    $content = & $Adb -s $Serial @Arguments 2>&1
    @($content) | Set-Content -LiteralPath (
        Join-Path $output "$Serial-$Name.txt"
    ) -Encoding UTF8
}

foreach ($serial in $Serials) {
    Capture-Text $serial "properties" @("shell", "getprop")
    Capture-Text $serial "network" @("shell", "ip", "-o", "addr", "show")
    Capture-Text $serial "package" @("shell", "dumpsys", "package", "com.kuma.motointercom")
    Capture-Text $serial "service" @("shell", "dumpsys", "activity", "services", "com.kuma.motointercom")
    Capture-Text $serial "audio" @("shell", "dumpsys", "audio")
    Capture-Text $serial "logcat" @("logcat", "-d", "-v", "threadtime")
    Capture-Text $serial "crash" @("logcat", "-b", "crash", "-d")
    Capture-Text $serial "ui-dump" @("exec-out", "uiautomator", "dump", "/dev/tty")

    $remoteScreenshot = "/sdcard/motointercom-b6.png"
    & $Adb -s $serial shell screencap -p $remoteScreenshot | Out-Null
    & $Adb -s $serial pull $remoteScreenshot (
        Join-Path $output "$serial-screen.png"
    ) | Out-Null
    & $Adb -s $serial shell rm -f $remoteScreenshot | Out-Null
}

$archive = "$output.zip"
Compress-Archive -Path (Join-Path $output "*") -DestinationPath $archive
[pscustomobject]@{
    serials = $Serials
    directory = (Resolve-Path -LiteralPath $output).Path
    archive = (Resolve-Path -LiteralPath $archive).Path
} | ConvertTo-Json -Depth 3
