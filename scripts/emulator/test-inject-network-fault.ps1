#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$FaultScript = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrEmpty($FaultScript)) {
    $FaultScript = Join-Path $PSScriptRoot "inject-network-fault.ps1"
}
$FaultScript = (Resolve-Path -LiteralPath $FaultScript).Path
$windowsPs = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
if (-not (Test-Path -LiteralPath $windowsPs)) { throw "Windows PowerShell is required" }
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$scratchLeaf = "kum75-" + [guid]::NewGuid().ToString("N") + " mock"
$scratch = [IO.Path]::GetFullPath((Join-Path $tempBase $scratchLeaf))
$null = [IO.Directory]::CreateDirectory($scratch)
$mockCmd = Join-Path $scratch "mock-adb.cmd"
$trace = Join-Path $scratch "commands.txt"
$envNames = @("KUM75_MOCK_LOG", "KUM75_MOCK_FAIL", "KUM75_ROOT_DENIED")
$previousEnv = @{}
foreach ($name in $envNames) {
    $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}
$checkedCases = 0

function Invoke-Probe {
    param($Plan, [int]$FailAt = 0, [string]$Device = "emulator-5554", [switch]$OmitMode)

    [IO.File]::WriteAllText($trace, "")
    $env:KUM75_MOCK_FAIL = if ($FailAt -gt 0) {
        "-s $Device " + $Plan.steps[$FailAt - 1]
    } else { "" }
    $launchArguments = @("-NoLogo", "-NoProfile", "-NonInteractive",
        "-ExecutionPolicy", "Bypass", "-File", $FaultScript, "-Serial", $Device)
    if (-not $OmitMode) { $launchArguments += @("-Mode", $Plan.mode) }
    $launchArguments += @("-Adb", $mockCmd)
    if (-not [string]::IsNullOrEmpty($Plan.interface)) {
        $launchArguments += @("-Interface", $Plan.interface)
    }
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $captured = @(& $windowsPs @launchArguments 2>&1)
        $childExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    [pscustomobject]@{
        exitCode = $childExit
        text = ($captured | Out-String)
        commands = @(Get-Content -LiteralPath $trace)
    }
}

function Assert-Failed {
    param($Probe, [string]$Label)
    if ($Probe.text -match '"status"\s*:\s*"APPLIED"') {
        throw "$Label emitted APPLIED after failure"
    }
    if ($Probe.exitCode -eq 0) { throw "$Label did not fail" }
}

function Assert-Commands {
    param($Probe, [string[]]$Expected, [string]$Label)
    if (($Probe.commands -join "`n") -cne ($Expected -join "`n")) {
        throw "$Label command sequence mismatch: $($Probe.commands -join '; ')"
    }
}

try {
    @'
@echo off
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0mock-adb.ps1" %*
exit /b %errorlevel%
'@ | Set-Content -LiteralPath $mockCmd -Encoding ASCII
    @'
$command = [string]::Join(" ", [string[]]$args)
[IO.File]::AppendAllText($env:KUM75_MOCK_LOG, $command + [Environment]::NewLine)
if ($command -eq $env:KUM75_MOCK_FAIL) {
    [Console]::Out.WriteLine("mock nonzero exit")
    exit 17
}
if ($env:KUM75_ROOT_DENIED -eq "1" -and $command.EndsWith(" root")) {
    [Console]::Out.WriteLine("adbd cannot run as root in production builds")
    exit 0
}
[Console]::Out.WriteLine("OK")
exit 0
'@ | Set-Content -LiteralPath (Join-Path $scratch "mock-adb.ps1") -Encoding ASCII
    $env:KUM75_MOCK_LOG = $trace
    $env:KUM75_ROOT_DENIED = "0"
    $plans = @(
        @{ mode="normal"; interface=""; steps=@("emu network speed full", "emu network delay none") },
        @{ mode="slow"; interface=""; steps=@("emu network speed edge", "emu network delay gprs") },
        @{ mode="offline"; interface=""; steps=@("shell svc wifi disable") },
        @{ mode="offline"; interface="wlan0"; steps=@("shell svc wifi disable") },
        @{ mode="offline"; interface="eth0"; steps=@("root", "wait-for-device", "shell ip link set dev eth0 down") },
        @{ mode="online"; interface=""; steps=@("shell svc wifi enable", "emu network speed full", "emu network delay none") },
        @{ mode="online"; interface="wlan0"; steps=@("shell svc wifi enable", "emu network speed full", "emu network delay none") },
        @{ mode="online"; interface="eth0"; steps=@("root", "wait-for-device", "shell ip link set dev eth0 up", "emu network speed full", "emu network delay none") }
    )
    foreach ($plan in $plans) {
        for ($failAt = 0; $failAt -le $plan.steps.Count; $failAt++) {
            $label = "$($plan.mode)/$($plan.interface)/fail=$failAt"
            $probe = Invoke-Probe -Plan $plan -FailAt $failAt
            $count = if ($failAt -eq 0) { $plan.steps.Count } else { $failAt }
            $expected = @($plan.steps[0..($count - 1)] | ForEach-Object { "-s emulator-5554 $_" })
            if ($failAt -gt 0) { Assert-Failed $probe $label }
            else {
                if ($probe.exitCode -ne 0) { throw "$label failed unexpectedly" }
                $json = $probe.text | ConvertFrom-Json
                if ($json.status -ne "APPLIED" -or $json.serial -ne "emulator-5554" -or $json.mode -ne $plan.mode) {
                    throw "$label returned invalid success metadata"
                }
            }
            Assert-Commands $probe $expected $label
            $checkedCases++
        }
    }
    foreach ($device in @("physical-device", "192.168.1.8:40133", "emulator-5554x")) {
        $probe = Invoke-Probe -Plan $plans[0] -Device $device
        Assert-Failed $probe "invalid serial $device"
        Assert-Commands $probe @() "invalid serial $device"
        if ($probe.text -notmatch "Only emulator serials are allowed") { throw "Serial guard was not the rejection source" }
        $checkedCases++
    }
    $probe = Invoke-Probe -Plan @{ mode="online"; interface="eth0;invalid"; steps=@() }
    Assert-Failed $probe "invalid interface"
    Assert-Commands $probe @() "invalid interface"
    $checkedCases++
    $env:KUM75_ROOT_DENIED = "1"
    $probe = Invoke-Probe -Plan $plans[4]
    Assert-Failed $probe "root refusal with exit zero"
    Assert-Commands $probe @("-s emulator-5554 root") "root refusal"
    $checkedCases++
    $env:KUM75_ROOT_DENIED = "0"
    $probe = Invoke-Probe -Plan $plans[0] -OmitMode
    Assert-Failed $probe "missing mode"
    Assert-Commands $probe @() "missing mode"
    $checkedCases++
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], "Process") }
    $cleanupInfo = [IO.DirectoryInfo]$scratch
    $expectedParent = $tempBase.TrimEnd([IO.Path]::DirectorySeparatorChar)
    if ($cleanupInfo.Parent.FullName.TrimEnd([IO.Path]::DirectorySeparatorChar) -ne $expectedParent -or
        $cleanupInfo.Name -ne $scratchLeaf) { throw "Refusing cleanup outside the owned temporary directory" }
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
[pscustomobject]@{ status="PASS"; cases=$checkedCases } | ConvertTo-Json
exit 0
