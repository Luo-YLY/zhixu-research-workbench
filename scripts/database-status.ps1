. (Join-Path $PSScriptRoot 'database-common.ps1')
if (-not (Test-Path -LiteralPath (Join-Path $script:DatabaseData 'PG_VERSION'))) {
    Write-Host 'NOT_INSTALLED: isolated PostgreSQL is not initialized. H2 remains available.'
    exit 0
}
if (-not (Test-DatabaseRunning)) {
    Write-Host 'STOPPED: data exists, local PostgreSQL is stopped.'
    exit 0
}
if (-not (Test-Path -LiteralPath $script:DatabaseConfig)) {
    Write-Host 'INCOMPLETE: PostgreSQL is running, but application database setup is incomplete.'
    exit 1
}
$config = Get-Content -LiteralPath $script:DatabaseConfig -Raw | ConvertFrom-Json
$sql = "SELECT json_build_object('database', current_database(), 'username', current_user, 'version', current_setting('server_version'), 'listenAddresses', current_setting('listen_addresses'), 'port', current_setting('port'), 'superuser', rolsuper, 'createDatabase', rolcreatedb, 'createRole', rolcreaterole) FROM pg_roles WHERE rolname=current_user;"
Invoke-DatabaseSql $config $sql
