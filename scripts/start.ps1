[CmdletBinding()]
param(
    [ValidateSet('auto','postgres','h2')][string]$Database = 'auto',
    [ValidateRange(1024,65535)][int]$Port = 18081
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localPath = Join-Path $projectRoot '.local'
$jarPath = Join-Path $projectRoot 'backend/target/research-workbench-backend-0.1.0.jar'
$statePath = Join-Path $localPath 'app.json'
$dbConfigPath = Join-Path $localPath 'database.json'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Run scripts/build.ps1 before starting the application.' }
New-Item -ItemType Directory -Path $localPath -Force | Out-Null

if (Test-Path -LiteralPath $statePath) {
    $existing = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $existingProcess = Get-Process -Id $existing.pid -ErrorAction SilentlyContinue
    if ($existingProcess) {
        if (-not $existing.processStartTicks -or -not $existing.executablePath -or
            [string]$existingProcess.StartTime.ToUniversalTime().Ticks -ne [string]$existing.processStartTicks -or
            $existingProcess.Path -ne $existing.executablePath -or $existing.jar -ne $jarPath) {
            throw 'Saved PID no longer identifies this application. Review .local/app.json; no process was changed.'
        }
        if ($Port -ne $existing.port) { throw 'Application is running on another port. Stop it before changing configuration.' }
        if ($Database -ne 'auto' -and $Database -ne $existing.database) { throw 'Application is running with another database. Stop it before changing configuration.' }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/system" -TimeoutSec 3
            if ($health.executionMode -eq 'DEMO') {
                Write-Host "Research Workbench is already running: http://127.0.0.1:$Port ($($existing.database))"
                return
            }
        } catch { }
        throw 'An existing application process has not become healthy. Inspect .local/app-stderr.log before restarting.'
    }
}

$listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if ($listeners | Where-Object { $_.Port -eq $Port }) { throw "Port $Port is already in use. Choose -Port or stop the owning application." }
if ($Database -eq 'auto') { $Database = if (Test-Path -LiteralPath $dbConfigPath) { 'postgres' } else { 'h2' } }
if ($Database -eq 'postgres') {
    if (-not (Test-Path -LiteralPath $dbConfigPath)) { throw 'PostgreSQL config missing. Run scripts/database-setup.ps1, or start with -Database h2.' }
    $dbConfig = Get-Content -LiteralPath $dbConfigPath -Raw | ConvertFrom-Json
    & (Join-Path $PSScriptRoot 'database-start.ps1')
}

$environmentNames = @('SPRING_PROFILES_ACTIVE','SERVER_PORT','WORKBENCH_DATA_DIR','DB_URL','DB_USERNAME','DB_PASSWORD')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:SPRING_PROFILES_ACTIVE = if ($Database -eq 'postgres') { 'postgres' } else { 'local' }
    $env:SERVER_PORT = [string]$Port
    $env:WORKBENCH_DATA_DIR = Join-Path $localPath 'data'
    if ($Database -eq 'postgres') {
        $env:DB_URL = "jdbc:postgresql://$($dbConfig.host):$($dbConfig.port)/$($dbConfig.database)"
        $env:DB_USERNAME = $dbConfig.username
        $env:DB_PASSWORD = $dbConfig.password
    }
    $javaCommand = (Get-Command java.exe -ErrorAction Stop).Source
    $appProcess = Start-Process -FilePath $javaCommand -ArgumentList @('-Dfile.encoding=UTF-8','-jar',('"' + $jarPath + '"')) `
        -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $localPath 'app-stdout.log') -RedirectStandardError (Join-Path $localPath 'app-stderr.log')
    $state = @{pid=$appProcess.Id; port=$Port; database=$Database; jar=$jarPath; executablePath=$javaCommand; processStartTicks=[string]$appProcess.StartTime.ToUniversalTime().Ticks; startedAt=[DateTime]::UtcNow.ToString('o')}
    [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}

for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if (-not (Get-Process -Id $appProcess.Id -ErrorAction SilentlyContinue)) { throw 'Application exited. Inspect .local/app-stdout.log and app-stderr.log.' }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/system" -TimeoutSec 2
        if ($health.executionMode -eq 'DEMO') {
            Write-Host "Research Workbench: http://127.0.0.1:$Port"
            Write-Host "Storage: $Database | Execution: DEMO | PID: $($appProcess.Id)"
            return
        }
    } catch { }
    Start-Sleep -Milliseconds 500
}
throw 'Application startup timed out. Inspect .local/app-stdout.log and app-stderr.log.'
