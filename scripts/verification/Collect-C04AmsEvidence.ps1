param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory)][ValidateSet('positive','busy-unbound','busy-bound','permission-unbound','permission-bound')][string]$Scenario,
    [string]$Package = 'com.kuma.motointercom',
    [ValidateSet('phone','microphone')][string]$MissingPermission = 'phone',
    [string]$AdbPath = 'C:\Users\kuma\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [Parameter(Mandatory)][string]$OutputRoot
)
# Disposable emulators only. No timeout settings are changed. All verdicts require PID/phase review.
$ErrorActionPreference = 'Stop'
function Invoke-TargetAdb {
    param([string[]]$AdbArgs)
    $result = @(& $AdbPath -s $Serial @AdbArgs 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($($AdbArgs -join ' ')): $($result -join ' ')" }
    return ($result -join "`n").Trim()
}
if ((Invoke-TargetAdb @('shell','getprop','ro.kernel.qemu')) -ne '1') { throw 'Emulator required' }
$sdk = [int](Invoke-TargetAdb @('shell','getprop','ro.build.version.sdk'))
if ($sdk -lt 26) { throw 'API 26+ required' }
$settings = Invoke-TargetAdb @('shell','dumpsys','activity','settings')
if ($settings -notmatch '(?m)^\s*service_start_foreground_timeout_ms=(\d+)') { throw 'Actual FGS timeout unavailable' }
$fgTimeout = [long]$Matches[1]
$anrDelay = 0L
if ($sdk -ge 30) {
    if ($settings -notmatch '(?m)^\s*service_start_foreground_anr_delay_ms=(\d+)') { throw 'Actual ANR delay unavailable' }
    $anrDelay = [long]$Matches[1]
}
# Conservatively include the possible one-time native timer extension, plus observation margin.
$windowMs = 2 * $fgTimeout + $anrDelay + 15000
$watchdogMs = $windowMs + 80000
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + $Scenario
if ($MissingPermission -eq 'microphone') { $runId += '-microphone' }
$runDirectory = Join-Path $OutputRoot $runId
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$settings | Set-Content (Join-Path $runDirectory 'activity-settings-before.txt') -Encoding utf8
Invoke-TargetAdb @('shell','getprop') | Set-Content (Join-Path $runDirectory 'getprop.txt') -Encoding utf8
Invoke-TargetAdb @('shell','am','force-stop',$Package) | Out-Null
$permissions = @('android.permission.RECORD_AUDIO','android.permission.READ_PHONE_STATE')
if ($sdk -ge 33) { $permissions += @('android.permission.NEARBY_WIFI_DEVICES','android.permission.POST_NOTIFICATIONS') }
else {
    $permissions += 'android.permission.ACCESS_FINE_LOCATION'
    if ($sdk -ge 31) { $permissions += 'android.permission.ACCESS_COARSE_LOCATION' }
}
if ($sdk -ge 31) { $permissions += 'android.permission.BLUETOOTH_CONNECT' }
foreach ($permission in $permissions) { Invoke-TargetAdb @('shell','pm','grant',$Package,$permission) | Out-Null }
$missingPermissionName = if ($MissingPermission -eq 'microphone') { 'android.permission.RECORD_AUDIO' } else { 'android.permission.READ_PHONE_STATE' }
if ($Scenario.StartsWith('permission-')) { Invoke-TargetAdb @('shell','pm','revoke',$Package,$missingPermissionName) | Out-Null }
Invoke-TargetAdb @('shell','dumpsys','package',$Package) | Set-Content (Join-Path $runDirectory 'package-before.txt') -Encoding utf8
Invoke-TargetAdb @('shell','settings','get','global','debug_app') | Set-Content (Join-Path $runDirectory 'debug-app.txt') -Encoding utf8
Invoke-TargetAdb @('shell','settings','get','global','wait_for_debugger') | Set-Content (Join-Path $runDirectory 'wait-debugger.txt') -Encoding utf8
$method = switch ($Scenario) {
    'positive' { 'legalForegroundStartPositiveControl' }
    'busy-unbound' { 'ownerBusyWithoutBinding' }
    'busy-bound' { 'ownerBusyWithAutoCreateBinding' }
    'permission-unbound' { 'permissionMissingWithoutBinding' }
    'permission-bound' { 'permissionMissingWithAutoCreateBinding' }
}
$logPath = Join-Path $runDirectory 'logcat-all.txt'
$logProcess = Start-Process $AdbPath -ArgumentList @('-s',$Serial,'logcat','-b','all','-v','threadtime','-T','1') -PassThru -WindowStyle Hidden -RedirectStandardOutput $logPath -RedirectStandardError (Join-Path $runDirectory 'logcat-stderr.txt')
$instrumentProcess = $null
$forcedStop = $false
$timer = [Diagnostics.Stopwatch]::StartNew()
$samples = @()
function Save-Snapshot {
    param([string]$Name)
    $before = $timer.ElapsedMilliseconds
    foreach ($kind in @('services','timers','processes')) {
        $adbArgs = @('shell','dumpsys','activity',$kind)
        if ($kind -eq 'services') { $adbArgs += $Package }
        try { Invoke-TargetAdb $adbArgs | Set-Content (Join-Path $runDirectory ($Name+'-'+$kind+'.txt')) -Encoding utf8 }
        catch { $_ | Out-String | Set-Content (Join-Path $runDirectory ($Name+'-'+$kind+'-error.txt')) }
    }
    $script:samples += [ordered]@{ name=$Name; hostMonotonicStartMs=$before; hostMonotonicEndMs=$timer.ElapsedMilliseconds }
}
try {
    $readyUntil = $timer.ElapsedMilliseconds + 5000
    while ((!(Test-Path $logPath) -or (Get-Item $logPath).Length -eq 0) -and $timer.ElapsedMilliseconds -lt $readyUntil -and !$logProcess.HasExited) { Start-Sleep -Milliseconds 100 }
    if ($logProcess.HasExited -or !(Test-Path $logPath) -or (Get-Item $logPath).Length -eq 0) { throw 'Log observer failed before probe' }
    $runner = 'com.kuma.motointercom.instrumentation/androidx.test.runner.AndroidJUnitRunner'
    $instrumentProcess = Start-Process $AdbPath -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-e','runId',$runId,'-e','observationMs',"$windowMs",'-e','missingPermission',$missingPermissionName,'-e','class',"com.kuma.motointercom.C04AmsProbeTest#$method",$runner) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runDirectory 'instrumentation.txt') -RedirectStandardError (Join-Path $runDirectory 'instrumentation-stderr.txt')
    $nextSample = 0L
    while (!$instrumentProcess.HasExited -and $timer.ElapsedMilliseconds -lt $watchdogMs) {
        if ($timer.ElapsedMilliseconds -ge $nextSample) {
            Save-Snapshot ('sample-'+$timer.ElapsedMilliseconds)
            $nextSample = $timer.ElapsedMilliseconds + 5000
        }
        Start-Sleep -Milliseconds 500
    }
    if (!$instrumentProcess.HasExited) {
        $forcedStop = $true
        Save-Snapshot 'before-observer-force-stop'
        Invoke-TargetAdb @('shell','am','force-stop',$Package) | Out-Null
        if (!$instrumentProcess.WaitForExit(5000)) { Stop-Process -Id $instrumentProcess.Id -Force }
    }
    # Retain independent logs after normal cleanup, runner failure, process death, or observer stop.
    $postUntil = $timer.ElapsedMilliseconds + 15000
    while ($timer.ElapsedMilliseconds -lt $postUntil) { Start-Sleep -Milliseconds 500 }
    Save-Snapshot 'after-post-window'
    foreach ($kind in @('lastanr','exit-info')) {
        $adbArgs = @('shell','dumpsys','activity',$kind)
        if ($kind -eq 'exit-info') { $adbArgs += $Package }
        try { Invoke-TargetAdb $adbArgs | Set-Content (Join-Path $runDirectory ($kind+'.txt')) -Encoding utf8 }
        catch { $_ | Out-String | Set-Content (Join-Path $runDirectory ($kind+'-error.txt')) }
    }
    Invoke-TargetAdb @('shell','logcat','-d','-b','crash','-v','threadtime') | Set-Content (Join-Path $runDirectory 'crash-buffer.txt') -Encoding utf8
} finally {
    if (!$logProcess.HasExited) { Stop-Process -Id $logProcess.Id -Force }
}
$logs = Get-Content $logPath -Raw -Encoding utf8
$runLines = @($logs -split "`n" | Where-Object { $_.Contains("C04 run=$runId ") })
$runLines | Set-Content (Join-Path $runDirectory 'probe-markers.txt') -Encoding utf8
@($logs -split "`n" | Where-Object { $_ -match 'ANR in |Reason:|ForegroundServiceDidNotStartInTimeException|did not then call Service.startForeground|Bringing down service|FATAL EXCEPTION|Process:|Killing .*motointercom|stopSelf|网络正在释放|缺少必要权限' }) | Set-Content (Join-Path $runDirectory 'system-signatures.txt') -Encoding utf8
[ordered]@{
    runId=$runId; serial=$Serial; sdk=$sdk; package=$Package; scenario=$Scenario; missingPermission=$missingPermissionName
    fgTimeoutMs=$fgTimeout; anrDelayMs=$anrDelay; nativeExtensionBudgetMs=$fgTimeout
    observationWindowMs=$windowMs; watchdogMs=$watchdogMs; observerForcedStop=$forcedStop
    hostDurationMs=$timer.ElapsedMilliseconds; snapshotIntervals=$samples
    timeBasis='Probe uptime/elapsed markers in ms; logcat threadtime is wall clock. Snapshot times are separate host Stopwatch intervals. Do not equate am_create_service or API_RETURN with timer.start.'
    verdict='REQUIRES_PID_PHASE_AND_BRANCH_REVIEW'
} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory 'observation.json') -Encoding utf8
Get-Item -LiteralPath $runDirectory | Select-Object FullName
