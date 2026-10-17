param(
    [string]$DeviceA = 'emulator-5554',
    [string]$DeviceB = 'emulator-5556',
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$BuildRoot = (Resolve-Path "$PSScriptRoot/../..").Path
)
$ErrorActionPreference = 'Stop'
if ($DeviceA -eq $DeviceB) { throw 'Use two distinct devices/processes' }
$package = 'com.kuma.motointercom'
$runner = 'com.kuma.motointercom.instrumentation/androidx.test.runner.AndroidJUnitRunner'
$local = Join-Path $PSScriptRoot '.local'
$jobs = @()
foreach ($entry in @(@($DeviceA, 'probe-a'), @($DeviceB, 'probe-b'))) {
    $serial, $name = $entry
    $config = Join-Path $local "$name.json"
    if (!(Test-Path -LiteralPath $config)) { throw "Missing private config: $name.json" }
    & $Adb -s $serial install -r "$BuildRoot/app/build/outputs/apk/debug/app-debug.apk"
    if ($LASTEXITCODE) { throw "App install failed on $serial" }
    & $Adb -s $serial install -r "$BuildRoot/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if ($LASTEXITCODE) { throw "Probe install failed on $serial" }
    & $Adb -s $serial push $config "/data/local/tmp/$name.json"
    if ($LASTEXITCODE) { throw "Config push failed on $serial" }
    & $Adb -s $serial shell "run-as $package sh -c 'mkdir -p files && cat /data/local/tmp/$name.json > files/relay-probe.json'"
    if ($LASTEXITCODE) { throw "Private config copy failed on $serial" }
    & $Adb -s $serial shell rm -f "/data/local/tmp/$name.json"
    $jobs += Start-Job -ArgumentList $Adb, $serial, $runner, $local, $name -ScriptBlock {
        param($adbPath, $target, $testRunner, $output, $probeName)
        & $adbPath -s $target shell am instrument -w -e class com.kuma.motointercom.RelayMediaProbeTest -e relayConfig relay-probe.json $testRunner |
            Set-Content -LiteralPath (Join-Path $output "$probeName-instrumentation.txt") -Encoding utf8
        if ($LASTEXITCODE) { throw "Instrumentation command failed on $target" }
    }
}
$jobs | Wait-Job -Timeout 150 | Receive-Job
if ($jobs.State -contains 'Running' -or $jobs.State -contains 'Failed') { throw 'Probe jobs timed out or failed' }
foreach ($entry in @(@($DeviceA, 'probe-a'), @($DeviceB, 'probe-b'))) {
    $serial, $name = $entry
    $instrumentationLog = Get-Content -LiteralPath (Join-Path $local "$name-instrumentation.txt") -Raw
    if ($instrumentationLog -notmatch 'OK \(1 test\)' -or $instrumentationLog -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw "Instrumentation did not pass on $serial; inspect .local/$name-instrumentation.txt"
    }
    $json = & $Adb -s $serial shell run-as $package cat files/relay-evidence.json
    if ($LASTEXITCODE) { throw "Cannot read evidence on $serial" }
    $result = ($json -join "`n") | ConvertFrom-Json
    $json | Set-Content -LiteralPath (Join-Path $local "$name-evidence.json") -Encoding utf8
    if (!$result.pass -or $result.transport.localType -ne 'relay' -or $result.transport.remoteType -ne 'relay' -or
        $result.decodedPeerToneFrames -lt 100 -or $result.rtpSentGrowth -le 1000 -or $result.rtpReceivedGrowth -le 1000) {
        throw "Native relay media evidence failed on $serial; inspect .local/$name-instrumentation.txt"
    }
    Write-Output "$serial PASS: selected relay/relay, bidirectional RTP growth, $($result.decodedPeerToneFrames) decoded peer-tone frames"
}
