. (Join-Path $PSScriptRoot 'database-common.ps1')
if (-not (Test-Path -LiteralPath (Join-Path $script:DatabaseData 'PG_VERSION'))) {
    Write-Host 'Local PostgreSQL has not been initialized; nothing to stop.'
    exit 0
}
if (-not (Test-DatabaseRunning)) {
    Write-Host 'Local PostgreSQL is already stopped.'
    exit 0
}
$pgCtl = Get-DatabaseExecutable 'pg_ctl'
$process = Start-Process -FilePath $pgCtl -ArgumentList @('stop', '-D', 'postgres-data', '-m', 'fast', '-w', '-t', '30') -WorkingDirectory $script:DatabaseLocalRoot -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $script:DatabaseLocalRoot 'postgres-stop.stdout.log') -RedirectStandardError (Join-Path $script:DatabaseLocalRoot 'postgres-stop.stderr.log')
if ($process.ExitCode -ne 0) { throw 'Local PostgreSQL did not stop cleanly. Check .local/postgres-stop.stderr.log.' }
Write-Host 'Local PostgreSQL stopped; data retained.'
