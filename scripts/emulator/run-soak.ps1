[CmdletBinding()]
param(
    [string[]]$Serials = @(),
    [ValidateRange(2, 120)]
    [int]$Minutes = 10,
    [ValidateRange(15, 600)]
    [int]$CycleSeconds = 45,
    [ValidateRange(1, 60)]
    [int]$OfflineSeconds = 3,
    [ValidateRange(1, 60)]
    [int]$RecoveryTimeoutSeconds = 40,
    [ValidateRange(1, 120)]
    [int]$JitterProbes = 2,
    [int]$TunnelPort = 9300,
    [string]$Adb = $(Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [string]$TestPackage = "com.kuma.motointercom.instrumentation",
    [string]$ResultsRoot = "build\emulator-results"
)

# Cross-emulator connection stability soak.
#   transport   : client reaches the server device through host loopback (10.0.2.2)
#                 backed by `adb forward`, so the client leg really traverses its eth0.
#   jitter      : `emu network delay/speed` throttles that leg; the exchange must still pass.
#   flap        : eth0 down/up on the client device; recovery latency is measured until the
#                 exchange passes again.
# Emulator shared-network (-shared-net-id) does not produce an interface on this SDK,
# so the tunnel is the only path that yields a real two-device socket.

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
Set-Location $repoRoot

if ($Serials.Count -eq 0) {
    $Serials = @(& $Adb devices | Select-String -Pattern "^emulator-\d+\s+device$" |
        ForEach-Object { ($_ -split "\s+")[0] })
}
if ($Serials.Count -lt 2) { throw "Soak requires at least two emulators" }
if ($Serials | Where-Object { $_ -notmatch "^emulator-\d+$" }) {
    throw "Only emulator serials are allowed: $($Serials -join ', ')"
}

$server = $Serials[0]
$client = $Serials[1]
$runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$exchangeClass = "com.kuma.motointercom.SyntheticAudioNetworkTest#exchange"
$hostAddress = "10.0.2.2"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$resultDir = Join-Path $ResultsRoot "$stamp-soak"
New-Item -ItemType Directory -Force -Path $resultDir | Out-Null

function Invoke-Exchange {
    param([int]$TimeoutSeconds = 60)
    $serverJob = Start-Job -ScriptBlock {
        param($adbPath, $serial, $cls, $port, $runnerName)
        & $adbPath -s $serial shell am instrument -w -r -e class $cls -e role server -e port $port $runnerName 2>&1
    } -ArgumentList $Adb, $server, $exchangeClass, $TunnelPort, $runner
    Start-Sleep -Seconds 3
    try {
        $clientOut = & $Adb -s $client shell am instrument -w -r -e class $exchangeClass `
            -e role client -e host $hostAddress -e port $TunnelPort $runner 2>&1 | Out-String
    } catch {
        $clientOut = "CLIENT-ERROR $($_.Exception.Message)"
    }
    $serverOut = Receive-Job -Job $serverJob -Wait -ErrorAction SilentlyContinue 2>&1 | Out-String
    try { Remove-Job -Job $serverJob -Force -ErrorAction SilentlyContinue } catch { }
    if (($clientOut + $serverOut) -match "INSTRUMENTATION_STATUS_CODE: -[12]") { return $false }
    return (($clientOut -match "OK \(1 test\)") -and ($serverOut -match "OK \(1 test\)"))
}

function Set-Tunnel {
    param([ValidateSet("up", "down")][string]$State)
    try { & $Adb -s $server forward --remove tcp:$TunnelPort *> $null } catch { }
    if ($State -eq "down") { return $true }
    & $Adb -s $server forward tcp:$TunnelPort tcp:$TunnelPort *> $null
    return ($LASTEXITCODE -eq 0)
}

function Set-ClientThrottle {
    param([ValidateSet("none", "slow")][string]$Mode)
    if ($Mode -eq "slow") {
        & $Adb -s $client emu network delay gprs *> $null
        & $Adb -s $client emu network speed edge *> $null
    } else {
        & $Adb -s $client emu network delay none *> $null
        & $Adb -s $client emu network speed full *> $null
    }
}

Set-Tunnel "up" | Out-Null

if (-not (Invoke-Exchange)) { throw "Baseline exchange failed before soak" }
Write-Output "SOAK-START minutes=$Minutes cycle=$CycleSeconds server=$server client=$client"

$deadline = (Get-Date).AddMinutes($Minutes)
$rows = New-Object System.Collections.Generic.List[object]
$cycle = 0

while ((Get-Date) -lt $deadline) {
    $cycle++
    $cycleStart = Get-Date

    # Jitter phase: throttle the client leg, the exchange must keep passing.
    Set-ClientThrottle "slow"
    $jitterOk = 0
    for ($i = 0; $i -lt $JitterProbes; $i++) {
        if (Invoke-Exchange) { $jitterOk++ }
    }
    Set-ClientThrottle "none"

    # Flap phase: sever the tunnelled peer, then time the recovery.
    $linkDown = Set-Tunnel "down"
    Start-Sleep -Seconds $OfflineSeconds
    $linkUp = Set-Tunnel "up"
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $recoveryMs = -1
    while ($watch.Elapsed.TotalSeconds -lt $RecoveryTimeoutSeconds) {
        if (Invoke-Exchange -TimeoutSeconds 20) { $recoveryMs = [int]$watch.ElapsedMilliseconds; break }
    }

    $rows.Add([pscustomobject]@{
            cycle        = $cycle
            jitterPassed = $jitterOk
            jitterProbes = $JitterProbes
            linkDown     = $linkDown
            linkUp       = $linkUp
            recoveryMs   = $recoveryMs
            recordedAt   = (Get-Date).ToString("o")
        })
    Write-Output ("CYCLE {0} jitter={1}/{2} recoveryMs={3}" -f $cycle, $jitterOk, $JitterProbes, $recoveryMs)

    $remaining = $CycleSeconds - ((Get-Date) - $cycleStart).TotalSeconds
    if ($remaining -gt 0) { Start-Sleep -Seconds ([int]$remaining) }
}

$recoveries = @($rows | Where-Object { $_.recoveryMs -ge 0 } | Select-Object -ExpandProperty recoveryMs)
$summary = [pscustomobject]@{
    startedAt          = $stamp
    minutes            = $Minutes
    cycles             = $cycle
    server             = $server
    client             = $client
    jitterProbes       = ($rows | Measure-Object -Property jitterProbes -Sum).Sum
    jitterPassed       = ($rows | Measure-Object -Property jitterPassed -Sum).Sum
    recoveries         = $recoveries.Count
    recoveryFailures   = $cycle - $recoveries.Count
    recoveryMinMs      = if ($recoveries.Count) { ($recoveries | Measure-Object -Minimum).Minimum } else { -1 }
    recoveryMaxMs      = if ($recoveries.Count) { ($recoveries | Measure-Object -Maximum).Maximum } else { -1 }
    recoveryAvgMs      = if ($recoveries.Count) { [int](($recoveries | Measure-Object -Average).Average) } else { -1 }
}

$rows | Export-Csv -LiteralPath (Join-Path $resultDir "cycles.csv") -NoTypeInformation -Encoding UTF8
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $resultDir "summary.json") -Encoding UTF8
Write-Output ($summary | Format-List | Out-String)
Write-Output "SOAK-DONE $resultDir"
