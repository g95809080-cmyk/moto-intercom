param(
    [string]$Ocr = 'ocr',
    [string]$OutputDirectory = 'logs/open-code-review'
)

$ErrorActionPreference = 'Stop'
$repository = (git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Run inside the MotoIntercom Git repository.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$version = & $Ocr --version
if ($LASTEXITCODE -ne 0) { throw 'Install @alibaba-group/open-code-review@1.12.13 first.' }
$version | Set-Content -LiteralPath (Join-Path $output 'tool-version.txt') -Encoding utf8
git rev-parse HEAD | Set-Content -LiteralPath (Join-Path $output 'source-sha.txt') -Encoding utf8
& $Ocr scan --preview --repo $repository --format json --color never --output (Join-Path $output 'preview.json')
if ($LASTEXITCODE -ne 0) { throw 'OCR scan preview failed.' }
$preview = Get-Content -LiteralPath (Join-Path $output 'preview.json') -Raw | ConvertFrom-Json
$paths = @($preview.files | Where-Object will_review | Select-Object -ExpandProperty path)
$batches = @()
for ($offset = 0; $offset -lt $paths.Count; $offset += 35) {
    $batch = @($paths[$offset..([Math]::Min($offset + 34, $paths.Count - 1))])
    $resolved = & $Ocr delegate rule --repo $repository --format json @batch
    if ($LASTEXITCODE -ne 0) { throw 'OCR rule resolution failed.' }
    $batches += (($resolved -join "`n") | ConvertFrom-Json)
}
$batches | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output 'rules.json') -Encoding utf8
Write-Output "OCR selected $($preview.reviewable_count) files; excluded $($preview.excluded_count) with reasons. Review coverage must be completed by the host agent."
