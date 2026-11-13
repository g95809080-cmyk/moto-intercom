[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$backend = Join-Path $repo 'backend\gme-control-plane'
Push-Location -LiteralPath $backend
try {
    & npm.cmd ci --ignore-scripts
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    & npm.cmd run check
    if ($LASTEXITCODE -ne 0) { throw 'Node syntax checks failed' }
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw 'Control-plane tests failed' }
    New-Item -ItemType Directory -Path '.build' -Force | Out-Null
    $staging = Join-Path $backend '.build/production'
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    Copy-Item -LiteralPath 'package.json','package-lock.json' -Destination $staging -Force
    & npm.cmd ci --ignore-scripts --omit=dev --prefix $staging
    if ($LASTEXITCODE -ne 0) { throw 'Production dependency installation failed' }
    @'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from hashlib import sha256
root = Path.cwd()
archive = root / '.build' / 'control-plane.zip'
with ZipFile(archive, 'w', ZIP_DEFLATED) as z:
    for name in ['package.json', 'package-lock.json']:
        z.write(root / name, name)
    for directory, base in [('src', root), ('node_modules', root / '.build' / 'production')]:
        for f in sorted((base / directory).rglob('*')):
            if f.is_file():
                z.write(f, f.relative_to(base).as_posix())
print(f'ZIP: {archive}')
print(f'SHA256: {sha256(archive.read_bytes()).hexdigest()}')
'@ | python -
    if ($LASTEXITCODE -ne 0) { throw 'Lambda packaging failed' }
} finally { Pop-Location }
