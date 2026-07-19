[CmdletBinding()]
param(
    [string]$Adb = $(Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [string]$StatePath = "build\emulator-cluster\cluster.json"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) {
    throw "Cluster state not found: $StatePath"
}
$state = Get-Content -LiteralPath $StatePath -Raw -Encoding UTF8 | ConvertFrom-Json

foreach ($node in $state.nodes) {
    if ($node.serial -notmatch "^emulator-\d+$") {
        throw "Invalid serial in cluster state: $($node.serial)"
    }
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $Adb -s $node.serial emu kill 2>$null | Out-Null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

Start-Sleep -Seconds 3
foreach ($node in $state.nodes) {
    $process = Get-Process -Id $node.processId -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -match "^(emulator|qemu)") {
        Stop-Process -Id $process.Id -Force
    }
    Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match "^(emulator|qemu)" -and
        $_.CommandLine -like "*-avd $($state.avdName)*" -and
        $_.CommandLine -like "*-port $($node.port)*"
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}

$state | Add-Member -NotePropertyName stoppedAt -NotePropertyValue (
    (Get-Date).ToString("o")
) -Force
$state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $StatePath -Encoding UTF8

[pscustomobject]@{
    state = (Resolve-Path -LiteralPath $StatePath).Path
    stopped = @($state.nodes.serial)
} | ConvertTo-Json -Depth 3
