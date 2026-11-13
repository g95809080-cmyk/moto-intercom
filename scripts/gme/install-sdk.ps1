param(
    [string]$Source = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ([string]::IsNullOrWhiteSpace($Source)) {
    $Source = Join-Path (Split-Path $repoRoot -Parent) "tmp\gme-g0-emulator\sample\app\libs"
}

$target = Join-Path $repoRoot "app\libs\gme"
$jar = Join-Path $Source "gmesdk.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    throw "gmesdk.jar not found under $Source"
}

New-Item -ItemType Directory -Path $target -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination $target -Force

$abis = @("arm64-v8a", "armeabi-v7a", "x86_64")
foreach ($abi in $abis) {
    $sourceAbi = Join-Path $Source $abi
    if (-not (Test-Path -LiteralPath $sourceAbi)) {
        throw "GME SDK ABI directory missing: $sourceAbi"
    }
    $targetAbi = Join-Path $target $abi
    New-Item -ItemType Directory -Path $targetAbi -Force | Out-Null
    $libraries = Get-ChildItem -LiteralPath $sourceAbi -File -Filter "*.so"
    if ($libraries.Count -eq 0) {
        throw "No GME native libraries found under $sourceAbi"
    }
    $libraries | Copy-Item -Destination $targetAbi -Force
}

Get-FileHash -LiteralPath (Join-Path $target "gmesdk.jar") -Algorithm SHA256 |
    Format-List Path, Hash
foreach ($abi in $abis) {
    $count = (Get-ChildItem -LiteralPath (Join-Path $target $abi) -File -Filter "*.so").Count
    Write-Host "$abi : $count native libraries"
}
