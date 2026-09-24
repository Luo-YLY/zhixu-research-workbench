. (Join-Path $PSScriptRoot 'database-common.ps1')
Start-ProjectDatabase
Write-Host 'Local PostgreSQL is running at 127.0.0.1:55432.'
