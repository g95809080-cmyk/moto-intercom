#requires -Version 5.1
[CmdletBinding()]
param([string]$EvidenceScript = "", [string]$CaseFilter = "*")

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
if (-not $EvidenceScript) { $EvidenceScript = Join-Path $PSScriptRoot "kum26_two_device_evidence.ps1" }
$EvidenceScript = (Resolve-Path -LiteralPath $EvidenceScript).Path
$windowsPs = Join-Path $env:SystemRoot "System32/WindowsPowerShell/v1.0/powershell.exe"
$pythonPath = (Get-Command python -CommandType Application | Select-Object -First 1).Source
$gitPath = (Get-Command git -CommandType Application | Select-Object -First 1).Source
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$scratchLeaf = "kum78-" + [guid]::NewGuid().ToString("N") + " mock"
$scratch = [IO.Path]::GetFullPath((Join-Path $tempBase $scratchLeaf))
$null = [IO.Directory]::CreateDirectory($scratch)
$trace = Join-Path $scratch "commands.txt"
$configPath = Join-Path $scratch "config.json"
$mockCmd = Join-Path $scratch "mock-adb.cmd"
$repository = Join-Path $scratch "repository"
$apk = Join-Path $scratch "test.apk"
$envNames = @("KUM78_CONFIG", "KUM78_TRACE", "PATH")
$previousEnv = @{}
foreach ($name in $envNames) { $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
$checkedCases = 0
$serialA = "USB-A"
$serialB = "USB-B"
$identityA = "10000000-0000-4000-8000-000000000001"
$identityB = "20000000-0000-4000-8000-000000000001"
$nicknameA = "30000000-0000-4000-8000-000000000001"
$nicknameB = "40000000-0000-4000-8000-000000000001"

function Save-Config {
    $config | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $configPath -Encoding UTF8
}

function New-Config {
    $devices = @{}
    foreach ($serial in @("USB-A", "USB-B", "192.168.1.62:42337", "192.168.1.61:40133",
        "192.168.1.62:45001", "192.168.1.61:45002")) {
        $isA = $serial -eq "USB-A" -or $serial.StartsWith("192.168.1.62:")
        $devices[$serial] = @{
            props=@{ 'ro.product.model'='Xiaomi test'; 'ro.build.version.release'='16';
                'ro.build.version.sdk'='36'; 'ro.kernel.qemu'=''; 'ro.boot.qemu'='' }
            identity=if ($isA) { $fixtures.identity_a } else { $fixtures.identity_b }
        }
    }
    return @{ devices=$devices; apk_hash=$apkHash; database=$fixtures.database;
        fail_command=''; fail_occurrence=1; replacements=@{}; git_fail=''; commit=$commit; block_summary='' }
}

function Invoke-Probe {
    param([string]$Mode, [string]$Directory, [string]$A = "USB-A", [string]$B = "USB-B")
    Save-Config
    [IO.File]::WriteAllText($trace, "")
    $launch = @("-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-File", $EvidenceScript, "-Mode", $Mode, "-DeviceA", $A, "-DeviceB", $B,
        "-AdbPath", $mockCmd, "-Repository", $repository, "-ApkPath", $apk)
    if ($Mode -ne 'Check') { $launch += @("-RunDirectory", $Directory, "-Scenario", "lan-a-requester") }
    if ($Mode -eq 'Stop') { $launch += @("-Result", "Pass", "-Notes", "Mock CLI regression") }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $windowsPs @launch 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    return [pscustomobject]@{ exitCode=$exitCode; text=($output | Out-String);
        commands=@(Get-Content -LiteralPath $trace) }
}

function Assert-Passed {
    param($Probe, [string]$Label)
    if ($Probe.exitCode -ne 0) { throw "$Label failed unexpectedly: $($Probe.text)" }
}

function Assert-Rejected {
    param($Probe, [string]$Directory, [string]$Label)
    if ($Probe.exitCode -eq 0) { throw "$Label falsely exited zero: $($Probe.text)" }
    $current = Join-Path $Directory 'scenario-result.txt'
    if (Test-Path -LiteralPath $current) {
        $status = Get-Content -LiteralPath $current -Raw
        if ($status -match '(?m)^result=Pass\s*$' -or $status -notmatch '(?m)^result=NotRun\s*$' -or
            $status -notmatch '(?m)^capture_status=capture_failed\s*$') {
            throw "$Label retained a current success or lacked explicit capture failure: $status"
        }
    }
    $summary = Join-Path $Directory 'summary.md'
    if ((Test-Path -LiteralPath $summary) -and
        (Get-Content -LiteralPath $summary -Raw) -match '(?m)^- Result: Pass\s*$') {
        throw "$Label retained a current summary Pass"
    }
}

function Get-CurrentCapture {
    param([string]$Directory)
    $status = Get-Content -LiteralPath (Join-Path $Directory 'scenario-result.txt') -Raw
    if ($status -notmatch '(?m)^result=Pass\s*$' -or $status -notmatch '(?m)^capture_status=complete\s*$') {
        throw "Completed positive capture was not published: $status"
    }
    $match = [regex]::Match($status, '(?m)^capture_directory=(.+)$')
    if (-not $match.Success) { throw 'Capture pointer missing' }
    return Join-Path $Directory $match.Groups[1].Value.Trim()
}

function Get-Snapshot {
    param([string]$Directory)
    return (@(Get-ChildItem -LiteralPath $Directory -File -Recurse | Sort-Object FullName |
        ForEach-Object { $_.FullName + '=' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }) -join "`n")
}

function Run-Case {
    param([string]$Name, [scriptblock]$Body)
    if ($Name -notlike $CaseFilter) { return }
    $script:config = New-Config
    $directory = Join-Path $scratch ("case-" + [guid]::NewGuid().ToString('N'))
    & $Body $directory
    $script:checkedCases++
    Write-Output "PASS $Name"
}

try {
    $env:KUM78_CONFIG = $configPath
    $env:KUM78_TRACE = $trace
    [IO.File]::WriteAllText($apk, 'actual local APK hash fixture')
    $apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    $null = New-Item -ItemType Directory -Path $repository
    & $gitPath -C $repository init -q
    if ($LASTEXITCODE -ne 0) { throw 'Fixture Git initialization failed' }
    & $gitPath -C $repository -c user.name=KUM78 -c user.email=kum78@example.invalid commit --allow-empty -qm fixture
    if ($LASTEXITCODE -ne 0) { throw 'Fixture Git commit failed' }
    $commit = (& $gitPath -C $repository rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Fixture Git HEAD read failed' }
    $generator = @'
import base64, json, sqlite3, sys
from pathlib import Path
root, schema_path = map(Path, sys.argv[1:])
root.mkdir()
def vi(n):
    result = bytearray()
    while n > 127:
        result.append((n & 127) | 128); n >>= 7
    return bytes(result) + bytes([n])
def string_field(n, value):
    if isinstance(value, str): value = value.encode()
    return vi(n * 8 + 2) + vi(len(value)) + value
def entry(key, value):
    return string_field(1, string_field(1, key) + string_field(2, string_field(5, value)))
def identity(device, nickname):
    return base64.b64encode(entry('nickname', nickname) + entry('device_id', device)).decode()
a='10000000-0000-4000-8000-000000000001'
b='20000000-0000-4000-8000-000000000001'
n='30000000-0000-4000-8000-000000000001'
schema=json.loads(schema_path.read_text(encoding='utf-8'))['database']
db=root/'fixture.db'
with sqlite3.connect(db) as connection:
    for table in schema['entities']:
        connection.execute(table['createSql'].replace('${TABLE_NAME}', table['tableName']))
    for query in schema['setupQueries']: connection.execute(query)
    connection.execute('pragma user_version=1')
    connection.execute("insert into paired_peers values (?, 'Peer', 'Phone', '', '1234', 1, 2, 1, 'LAN', 0)", (b,))
    connection.execute('create table unused(value text)')
    connection.execute("insert into unused values ('test')")
    unused_page=connection.execute("select rootpage from sqlite_master where name='unused'").fetchone()[0]
    page_size=connection.execute('pragma page_size').fetchone()[0]
data=db.read_bytes()
damaged=bytearray(data)
# Damage only an unused table: paired_peers can still be queried, integrity cannot pass.
offset=(unused_page-1)*page_size
damaged[offset+1:offset+3]=(page_size-1).to_bytes(2,'big')
corrupt=root/'corrupt.db'; corrupt.write_bytes(damaged)
with sqlite3.connect(corrupt) as connection:
    integrity=connection.execute('pragma integrity_check').fetchall()
    assert integrity != [('ok',)], integrity
    assert connection.execute('select remoteDeviceId from paired_peers').fetchone()[0] == b
print(json.dumps({
    'identity_a': identity(a,n), 'identity_b': identity(b,'40000000-0000-4000-8000-000000000001'),
    'same_identity_b': identity(a,'40000000-0000-4000-8000-000000000001'),
    'nickname_only':base64.b64encode(entry('nickname', n)).decode(),
    'duplicate_identity':base64.b64encode(entry('device_id', a)*2).decode(),
    'noncanonical': identity('AAAAAAAA-0000-4000-8000-000000000001',n),
    'truncated':base64.b64encode(b'\x0a\xff').decode(),
    'wrong_value_type':base64.b64encode(string_field(1,string_field(1,'device_id')+string_field(2,b'\x08\x01'))).decode(),
    'database':base64.b64encode(data).decode(),
    'corrupt_database':base64.b64encode(damaged).decode(),
}))
'@
    $fixtureArguments = @((Join-Path $scratch 'data'),
        (Join-Path (Split-Path -Parent $PSScriptRoot) 'app/schemas/com.kuma.motointercom.PairingDatabase/1.json'))
    $fixtures = (($generator | & $pythonPath - @fixtureArguments) | Out-String) | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Actual DataStore/Room fixture generation failed' }
    @'
import json, os, sys
from pathlib import Path
config=json.loads(Path(os.environ['KUM78_CONFIG']).read_text(encoding='utf-8-sig'))
args=sys.argv[1:]
if args and args[0]=='--git':
    command=' '.join(args[3:])
    if command=='rev-parse HEAD': print(config['commit'])
    elif command!='status --porcelain': raise SystemExit(19)
    raise SystemExit(17 if command==config['git_fail'] else 0)
command=' '.join(args)
trace=Path(os.environ['KUM78_TRACE'])
prior=trace.read_text().splitlines()
with trace.open('a') as output: output.write(command+'\n')
serial=None
if args[:1]==['-s']: serial=args[1]; args=args[2:]
device=config['devices'].get(serial)
if args==['devices','-l']: text='List of devices attached\n'+ '\n'.join(s+' device' for s in config['devices'])
elif device is None: raise SystemExit(19)
elif args==['get-state']: text='device'
elif args[:2]==['shell','getprop']: text=device['props'].get(args[2],'')
elif args[:3]==['shell','pm','path']: text='package:/data/app/motointercom/base.apk'
elif args[:2]==['shell','sha256sum']: text=config['apk_hash']+'  /data/app/motointercom/base.apk'
elif args[:2]==['shell','run-as'] and args[3:4]==['base64']:
    text=device['identity'] if args[4].startswith('files/') else (config['database'] if args[4].endswith('.db') else '')
elif args[:1]==['logcat']: text='attemptId=test CONNECT_ACCEPT WebRTC'
elif args[:2]==['shell','dumpsys']: text='com.kuma.motointercom MODE_IN_COMMUNICATION'
else: raise SystemExit(19)
occurrence=prior.count(command)+1
replacement=config['replacements'].get(command)
if replacement is not None and occurrence>=replacement['occurrence']: text=replacement['text']
if config['block_summary'] and args[:2]==['shell','sha256sum'] and serial=='USB-B' and occurrence==2:
    directory=Path(config['block_summary'])
    status=(directory/'scenario-result.txt').read_text(encoding='utf-8-sig')
    capture=next(line.split('=',1)[1] for line in status.splitlines() if line.startswith('capture_id='))
    (directory/'captures'/capture/'summary.md').mkdir()
print(text)
raise SystemExit(17 if command==config['fail_command'] and occurrence==config['fail_occurrence'] else 0)
'@ | Set-Content -LiteralPath (Join-Path $scratch 'mock-adb.py') -Encoding ASCII
    @("@echo off", ('"' + $pythonPath + '" -B "%~dp0mock-adb.py" %*'), 'exit /b %errorlevel%') |
        Set-Content -LiteralPath $mockCmd -Encoding ASCII

    Run-Case 'same-serial' {
        param($directory)
        $probe=Invoke-Probe Start $directory $serialA $serialA
        Assert-Rejected $probe $directory 'same serial'
        if ($probe.commands.Count -ne 0 -or $probe.text -notmatch 'different nonempty serials') { throw 'Serial guard did not run before ADB' }
    }
    Run-Case 'physical-distinct-identity' {
        param($directory)
        Assert-Passed (Invoke-Probe Start $directory) 'Start'
        $manifest=Get-Content -LiteralPath (Join-Path $directory 'manifest.txt') -Raw
        if ($manifest -notmatch "device_a_identity=$identityA" -or $manifest -notmatch "device_b_identity=$identityB" -or
            $manifest -match "device_a_identity=$nicknameA" -or $manifest -notmatch "commit_sha=$commit" -or
            $manifest -notmatch "apk_sha256=$apkHash") { throw 'Source/hash/exact device_id metadata not captured' }
        Assert-Passed (Invoke-Probe Stop $directory) 'Stop'
        $capture=Get-CurrentCapture $directory
        if ((Get-Content -LiteralPath (Join-Path $capture 'summary.md') -Raw) -notmatch 'two physical Android devices' -or
            (Get-Content -LiteralPath (Join-Path $capture 'database-check.txt') -Raw) -notmatch 'integrity=ok' -or
            (Get-Content -LiteralPath (Join-Path $capture 'database-check.txt') -Raw) -notmatch 'remoteNickname,deviceName,localAlias') {
            throw 'Completed actual Room-schema capture missing'
        }
        $snapshot=Get-Snapshot $capture
        $probe=Invoke-Probe Start $directory
        if ($probe.exitCode -eq 0 -or $probe.text -notmatch 'new or empty directory') { throw 'Start reused evidence' }
        if ((Get-Snapshot $capture) -cne $snapshot) { throw 'Start overwrote historical evidence' }
    }
    Run-Case 'wireless-port-change' {
        param($directory)
        Assert-Passed (Invoke-Probe Start $directory '192.168.1.62:42337' '192.168.1.61:40133') 'wireless Start'
        Assert-Passed (Invoke-Probe Stop $directory '192.168.1.62:45001' '192.168.1.61:45002') 'wireless Stop'
        $null=Get-CurrentCapture $directory
    }
    Run-Case 'check' { param($directory); Assert-Passed (Invoke-Probe Check $directory) 'Check' }
    foreach ($identityCase in @('same_identity_b','nickname_only','duplicate_identity','noncanonical','truncated','wrong_value_type','invalid_base64')) {
        Run-Case "identity-$identityCase" {
            param($directory)
            $value=if ($identityCase -eq 'invalid_base64') { 'not-base64!' } else { $fixtures.$identityCase }
            $config.devices[$serialB].identity=$value
            $probe=Invoke-Probe Start $directory
            Assert-Rejected $probe $directory $identityCase
            if (Test-Path -LiteralPath (Join-Path $directory 'manifest.txt')) { throw 'Invalid identity acquired a physical manifest' }
        }
    }
    Run-Case 'emulator-serial' {
        param($directory)
        $probe=Invoke-Probe Start $directory 'emulator-5554' $serialB
        Assert-Rejected $probe $directory 'emulator serial'
        if ($probe.commands.Count -ne 0 -or $probe.text -notmatch 'Emulator serial') { throw 'Emulator serial guard not executed' }
    }
    foreach ($property in @('ro.kernel.qemu','ro.boot.qemu')) {
        foreach ($marker in @('1','unknown')) {
            Run-Case "tcp-emulator-$property-$marker" {
                param($directory)
                $config.devices['192.168.1.62:42337'].props[$property]=$marker
                Assert-Rejected (Invoke-Probe Start $directory '192.168.1.62:42337' $serialB) $directory 'TCP emulator marker'
            }
        }
    }
    foreach ($property in @('ro.product.model','ro.build.version.release','ro.build.version.sdk')) {
        Run-Case "metadata-$property" {
            param($directory)
            $config.devices[$serialB].props[$property]=''
            Assert-Rejected (Invoke-Probe Start $directory) $directory 'missing required metadata'
        }
    }
    Run-Case 'metadata-sdk-malformed' {
        param($directory)
        $config.devices[$serialA].props['ro.build.version.sdk']='android36'
        Assert-Rejected (Invoke-Probe Start $directory) $directory 'non-numeric SDK'
    }
    Run-Case 'database-integrity' {
        param($directory)
        $config.database=$fixtures.corrupt_database
        Assert-Rejected (Invoke-Probe Start $directory) $directory 'actual non-ok SQLite integrity'
    }
    $commands=@("-s $serialA get-state", "-s $serialB get-state", 'devices -l',
        "-s $serialA shell getprop ro.product.model", "-s $serialB shell getprop ro.build.version.sdk",
        "-s $serialA shell getprop ro.kernel.qemu", "-s $serialB shell getprop ro.boot.qemu",
        "-s $serialA shell run-as com.kuma.motointercom base64 files/datastore/local_identity.preferences_pb",
        "-s $serialB shell run-as com.kuma.motointercom base64 files/datastore/local_identity.preferences_pb",
        "-s $serialA shell pm path com.kuma.motointercom", "-s $serialB shell pm path com.kuma.motointercom",
        "-s $serialA shell sha256sum /data/app/motointercom/base.apk", "-s $serialB shell sha256sum /data/app/motointercom/base.apk")
    foreach ($command in $commands) {
        Run-Case "native-exit-$command" {
            param($directory)
            $config.fail_command=$command
            $probe=Invoke-Probe Start $directory
            Assert-Rejected $probe $directory 'valid stdout with native exit 17'
            if ($probe.commands.Count -eq 0 -or $probe.commands[-1] -cne $command -or $probe.text -notmatch 'adb failed') {
                throw 'Capture continued after native command failure, or rejected for wrong reason'
            }
        }
    }
    Run-Case 'prior-pass-invalidated' {
        param($directory)
        Assert-Passed (Invoke-Probe Start $directory) 'prior Start'
        Assert-Passed (Invoke-Probe Stop $directory) 'prior Stop'
        if ((Get-Content -LiteralPath (Join-Path $directory 'scenario-result.txt') -Raw) -notmatch '(?m)^result=Pass\s*$') {
            throw 'Prior actual CLI did not produce Pass'
        }
        $config.fail_command="-s $serialB shell dumpsys audio"
        Assert-Rejected (Invoke-Probe Stop $directory) $directory 'prior Pass must become NotRun on a middle failure'
    }
    foreach ($failure in @('early','middle','late-hash','late-identity','late-metadata','replacement','converged','summary-write')) {
        Run-Case "repeated-stop-$failure" {
            param($directory)
            Assert-Passed (Invoke-Probe Start $directory) 'repeated Start'
            Assert-Passed (Invoke-Probe Stop $directory) 'first Stop'
            $capture=Get-CurrentCapture $directory
            $snapshot=Get-Snapshot $capture
            switch ($failure) {
                'early' { $config.fail_command="-s $serialA get-state" }
                'middle' { $config.fail_command="-s $serialB shell dumpsys audio" }
                'late-hash' {
                    $command="-s $serialB shell sha256sum /data/app/motointercom/base.apk"
                    $config.replacements[$command]=@{ occurrence=2; text=('0'*64)+'  /data/app/motointercom/base.apk' }
                }
                'late-identity' {
                    $command="-s $serialA shell run-as com.kuma.motointercom base64 files/datastore/local_identity.preferences_pb"
                    $config.replacements[$command]=@{ occurrence=2; text=$fixtures.identity_b }
                }
                'late-metadata' {
                    $command="-s $serialA shell getprop ro.product.model"
                    $config.replacements[$command]=@{ occurrence=2; text='' }
                }
                'replacement' { $config.devices[$serialA].identity=$fixtures.identity_b; $config.devices[$serialB].identity=$fixtures.identity_a }
                'converged' { $config.devices[$serialB].identity=$fixtures.same_identity_b }
                'summary-write' { $config.block_summary=$directory }
            }
            $probe=Invoke-Probe Stop $directory
            Assert-Rejected $probe $directory "repeated Stop $failure"
            if ($failure -in @('early','middle') -and $probe.commands[-1] -cne $config.fail_command) {
                throw 'Repeated Stop continued after command failure'
            }
            if ((Get-Snapshot $capture) -cne $snapshot) { throw 'Repeated Stop overwrote a prior complete snapshot' }
            # A fresh successful Stop can follow a failed attempt, in a third distinct directory.
            $script:config=New-Config
            Assert-Passed (Invoke-Probe Stop $directory) 'recovery Stop'
            $newCapture=Get-CurrentCapture $directory
            if ($newCapture -ceq $capture -or (Get-Snapshot $capture) -cne $snapshot) { throw 'Snapshot recovery lost immutable history' }
        }
    }
    foreach ($gitCommand in @('status --porcelain','rev-parse HEAD')) {
        Run-Case "git-exit-$gitCommand" {
            param($directory)
            $config.git_fail=$gitCommand
            $mockGit=Join-Path $scratch 'git.cmd'
            @('@echo off', ('"' + $pythonPath + '" -B "%~dp0mock-adb.py" --git %*'), 'exit /b %errorlevel%') |
                Set-Content -LiteralPath $mockGit -Encoding ASCII
            $env:PATH=$scratch+[IO.Path]::PathSeparator+$previousEnv['PATH']
            try {
                $probe=Invoke-Probe Start $directory
                Assert-Rejected $probe $directory 'Git valid stdout with exit 17'
                if ($probe.text -notmatch 'Git source verification failed') { throw 'Git exit guard not reached' }
            } finally { $env:PATH=$previousEnv['PATH']; Remove-Item -LiteralPath $mockGit -Force }
        }
    }
    if ($checkedCases -eq 0) { throw 'Case filter selected no regressions' }
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], 'Process') }
    $cleanup=[IO.DirectoryInfo]$scratch
    if ($cleanup.Parent.FullName.TrimEnd([IO.Path]::DirectorySeparatorChar) -ne $tempBase -or
        $cleanup.Name -ne $scratchLeaf) { throw 'Refusing cleanup outside owned fixture directory' }
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
[pscustomobject]@{ status='PASS'; cases=$checkedCases; target='Windows PowerShell 5.1 actual CLI / mock ADB only' } | ConvertTo-Json
