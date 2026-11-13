[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DynamoDbLocalDirectory,
    [string]$JavaPath = 'F:\Android\jbr\bin\java.exe',
    [int]$Port = 8042
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$backend = Join-Path $repo 'backend\gme-control-plane'
$local = (Resolve-Path -LiteralPath $DynamoDbLocalDirectory).Path
if (!(Test-Path -LiteralPath (Join-Path $local 'DynamoDBLocal.jar'))) { throw 'DynamoDBLocal.jar not found' }
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be in 1024..65535' }
$build = Join-Path $backend '.build'
New-Item -ItemType Directory -Path $build -Force | Out-Null
$existingEndpoint = $env:GME_TEST_DYNAMODB_ENDPOINT
$portProbe = [System.Net.Sockets.TcpClient]::new()
try {
    $portProbe.Connect('127.0.0.1', $Port)
    throw "Port $Port is already occupied"
} catch [System.Net.Sockets.SocketException] {
    # Connection refusal confirms that this test can own the port.
} finally { $portProbe.Dispose() }
$process = $null
try {
    $process = Start-Process -FilePath $JavaPath -WindowStyle Hidden -PassThru -WorkingDirectory $local `
        -ArgumentList @('-Xmx256m', '-Djava.library.path=./DynamoDBLocal_lib', '-jar', 'DynamoDBLocal.jar', '-inMemory', '-sharedDb', '-port', "$Port", '-disableTelemetry') `
        -RedirectStandardOutput (Join-Path $build 'dynamodb-local.stdout.log') `
        -RedirectStandardError (Join-Path $build 'dynamodb-local.stderr.log')
    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        if ($process.HasExited) { throw 'DynamoDB Local exited; inspect .build/dynamodb-local.stderr.log' }
        $probe = [System.Net.Sockets.TcpClient]::new()
        try { $probe.Connect('127.0.0.1', $Port); $ready = $true; break }
        catch { Start-Sleep -Milliseconds 250 }
        finally { $probe.Dispose() }
    }
    if (!$ready) { throw 'DynamoDB Local did not become ready' }
    $env:GME_TEST_DYNAMODB_ENDPOINT = "http://127.0.0.1:$Port"
    Push-Location -LiteralPath $backend
    try {
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw 'DynamoDB Local integration tests failed' }
    } finally { Pop-Location }
} finally {
    $env:GME_TEST_DYNAMODB_ENDPOINT = $existingEndpoint
    if ($null -ne $process -and !$process.HasExited) { Stop-Process -Id $process.Id }
}
