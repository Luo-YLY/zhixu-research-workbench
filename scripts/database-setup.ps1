param(
    [string]$ArchivePath,
    [string]$Proxy,
    [string]$ExpectedSha256
)
. (Join-Path $PSScriptRoot 'database-common.ps1')

# Official EDB binary page verified on 2026-09-22. No Windows service or global PATH changes.
$sourcePage = 'https://www.enterprisedb.com/download-postgresql-binaries'
$sourceLink = 'https://sbp.enterprisedb.com/getfile.jsp?fileid=1260569'
$downloadUrl = 'https://get.enterprisedb.com/postgresql/postgresql-17.11-4-windows-x64-binaries.zip'
$version = '17.11'
$expectedBytes = 379726839
New-Item -ItemType Directory -Force -Path $script:DatabaseLocalRoot | Out-Null

if (Test-Path -LiteralPath $script:DatabaseConfig) {
    Start-ProjectDatabase
    & (Join-Path $PSScriptRoot 'database-status.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Existing application database failed verification.' }
    exit 0
}

if (-not (Test-Path -LiteralPath (Join-Path $script:DatabaseBin 'initdb.exe'))) {
    if (-not $ArchivePath) {
        $downloads = Join-Path $script:DatabaseLocalRoot 'postgres-downloads'
        New-Item -ItemType Directory -Force -Path $downloads | Out-Null
        $ArchivePath = Join-Path $downloads 'postgresql-17.11-4-windows-x64-binaries.zip'
        if (-not (Test-Path -LiteralPath $ArchivePath)) {
            $partial = $ArchivePath + '.partial'
            if (Test-Path -LiteralPath $partial) {
                throw 'A partial PostgreSQL download already exists. Supply a complete official ZIP using -ArchivePath; existing files are retained.'
            }
            $request = @{ Uri = $downloadUrl; OutFile = $partial; UseBasicParsing = $true; TimeoutSec = 240; ErrorAction = 'Stop' }
            if ($Proxy) { $request.Proxy = $Proxy }
            Write-Host 'Downloading the official PostgreSQL portable ZIP into project .local...'
            try { Invoke-WebRequest @request } catch { throw 'Official PostgreSQL download failed. Supply the official ZIP with -ArchivePath when available. H2 remains available; no application database config was written.' }
            if ((Get-Item -LiteralPath $partial).Length -ne $expectedBytes) { throw 'Official archive size mismatch. Partial file retained; no database was initialized.' }
            $safePartial = Assert-DatabaseLocalPath $partial
            $safeArchive = Assert-DatabaseLocalPath $ArchivePath
            Move-Item -LiteralPath $safePartial -Destination $safeArchive
        }
    }
    $ArchivePath = [IO.Path]::GetFullPath($ArchivePath)
    if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) { throw 'PostgreSQL archive does not exist. No database was initialized.' }
    if ((Get-Item -LiteralPath $ArchivePath).Length -ne $expectedBytes) { throw 'Archive size differs from the verified official PostgreSQL 17.11-4 Windows x64 ZIP.' }
    $hash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ExpectedSha256 -and $hash -ne $ExpectedSha256.ToLowerInvariant()) { throw 'PostgreSQL archive SHA256 mismatch.' }
    if (Test-Path -LiteralPath $script:DatabaseBinaryRoot) {
        throw 'A PostgreSQL binary directory already exists but is incomplete. It is retained for review; no files are overwritten.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $required = @('pgsql/bin/initdb.exe', 'pgsql/bin/pg_ctl.exe', 'pgsql/bin/psql.exe', 'pgsql/bin/postgres.exe')
        $names = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        foreach ($name in $required) { if ($name -notin $names) { throw "Official PostgreSQL ZIP is missing $name" } }
        foreach ($entry in $archive.Entries) {
            $entryPath = [IO.Path]::GetFullPath((Join-Path $script:DatabaseBinaryRoot $entry.FullName))
            $binaryBoundary = $script:DatabaseBinaryRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
            if (-not $entryPath.StartsWith($binaryBoundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe path in PostgreSQL archive.' }
        }
    } finally { $archive.Dispose() }
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $script:DatabaseBinaryRoot
    $initdb = Get-DatabaseExecutable 'initdb'
    $binaryVersion = (& $initdb --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $binaryVersion -notmatch '^initdb \(PostgreSQL\) 17\.11$') { throw 'Extracted PostgreSQL binary version did not match 17.11; database initialization stopped.' }
    $metadata = [ordered]@{ version = $version; build = '17.11-4'; sourcePage = $sourcePage; sourceLink = $sourceLink; downloadUrl = $downloadUrl; archiveBytes = $expectedBytes; archiveSha256 = $hash; checksumProvenance = 'Locally computed; not an independently published publisher checksum'; downloadedArchive = $ArchivePath; installedAt = [DateTime]::UtcNow.ToString('o'); binaryVersion = $binaryVersion }
    [IO.File]::WriteAllText((Join-Path $script:DatabaseLocalRoot 'postgres-install.json'), ($metadata | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
}

$initdb = Get-DatabaseExecutable 'initdb'
$actualVersion = (& $initdb --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $actualVersion -notmatch '^initdb \(PostgreSQL\) 17\.11$') { throw 'Installed PostgreSQL binary version is not the expected 17.11.' }

if (-not (Test-Path -LiteralPath (Join-Path $script:DatabaseData 'PG_VERSION'))) {
    if (Test-Path -LiteralPath $script:DatabaseData) { throw 'An uninitialized or incomplete PostgreSQL data directory exists. It is retained for review.' }
    $admin = [ordered]@{ host = '127.0.0.1'; port = $script:DatabasePort; database = 'postgres'; username = 'research_admin'; password = New-DatabasePassword }
    # Retain bootstrap credentials if later steps fail, so an initialized cluster is recoverable.
    Write-DatabasePrivateJson $script:DatabaseAdminConfig $admin
    $passwordFile = Assert-DatabaseLocalPath (Join-Path $script:DatabaseLocalRoot 'postgres-init.password')
    [IO.File]::WriteAllText($passwordFile, $admin.password, (New-Object Text.UTF8Encoding($false)))
    try {
        Push-Location $script:DatabaseLocalRoot
        try {
            & $initdb -D 'postgres-data' -U 'research_admin' --encoding=UTF8 --locale=C --auth=scram-sha-256 --no-clean --pwfile='postgres-init.password'
            if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initialization failed. Existing files and private bootstrap config were retained.' }
        } finally { Pop-Location }
    } finally { if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile -Force } }
}
if (-not (Test-Path -LiteralPath $script:DatabaseAdminConfig)) { throw 'Existing cluster has no private administrator config; automatic modification is refused.' }
if ((Get-Content -LiteralPath (Join-Path $script:DatabaseData 'PG_VERSION') -Raw).Trim() -ne '17') { throw 'Existing PostgreSQL data belongs to another major version; it is retained without changes.' }
$settingsPath = Join-Path $script:DatabaseData 'postgresql.conf'
if ((Get-Content -LiteralPath $settingsPath -Raw) -notmatch '# Research Workbench private local instance') {
    $settings = "`n# Research Workbench private local instance`nlisten_addresses = '127.0.0.1'`nport = 55432`nmax_connections = 30`nshared_buffers = '128MB'`npassword_encryption = 'scram-sha-256'`n"
    [IO.File]::AppendAllText($settingsPath, $settings, (New-Object Text.UTF8Encoding($false)))
}
$admin = Get-Content -LiteralPath $script:DatabaseAdminConfig -Raw | ConvertFrom-Json
Start-ProjectDatabase
$roleExists = Invoke-DatabaseSql $admin "SELECT count(*) FROM pg_roles WHERE rolname='research_app';"
$databaseOwner = Invoke-DatabaseSql $admin "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='research_workbench';"
$appBootstrapPath = Join-Path $script:DatabaseLocalRoot 'postgres-app-bootstrap.json'
if (Test-Path -LiteralPath $appBootstrapPath) {
    $app = Get-Content -LiteralPath $appBootstrapPath -Raw | ConvertFrom-Json
    if ($app.host -ne '127.0.0.1' -or $app.port -ne 55432 -or $app.database -ne 'research_workbench' -or $app.username -ne 'research_app' -or $app.password -notmatch '^[0-9a-f]{64}$') {
        throw 'Saved application bootstrap config is invalid. No database objects were changed.'
    }
} else {
    if ($roleExists -ne '0' -or $databaseOwner) {
        throw 'Application role or database already exists without this installer bootstrap credentials. Existing objects are retained; automatic adoption is refused.'
    }
    $app = [ordered]@{ host = '127.0.0.1'; port = $script:DatabasePort; database = 'research_workbench'; username = 'research_app'; password = New-DatabasePassword }
    # Save before CREATE ROLE so partial setup never loses the application password.
    Write-DatabasePrivateJson $appBootstrapPath $app
}
if ($databaseOwner -and $databaseOwner -ne 'research_app') {
    throw 'research_workbench has a different owner. Existing database is retained; automatic modification is refused.'
}
if ($roleExists -eq '0') {
    Invoke-DatabaseSql $admin "CREATE ROLE research_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$($app.password)';" -Quiet
} else {
    $roleFlags = Invoke-DatabaseSql $admin "SELECT rolcanlogin || ':' || rolsuper || ':' || rolcreatedb || ':' || rolcreaterole || ':' || rolreplication FROM pg_roles WHERE rolname='research_app';"
    if ($roleFlags -ne 'true:false:false:false:false') { throw 'Existing application role privileges differ from this installer. Automatic modification is refused.' }
    # Prove the saved bootstrap credentials authenticate before adopting an existing role.
    # A role-only partial setup still has default CONNECT on postgres; later stages use its own database.
    $probe = [ordered]@{ host = $app.host; port = $app.port; database = 'postgres'; username = $app.username; password = $app.password }
    if ($databaseOwner) { $probe.database = 'research_workbench' }
    $authenticatedUser = Invoke-DatabaseSql $probe 'SELECT current_user;'
    if ($authenticatedUser -ne 'research_app') { throw 'Existing role does not match saved bootstrap credentials. No objects were modified.' }
}
if (-not $databaseOwner) { Invoke-DatabaseSql $admin 'CREATE DATABASE research_workbench OWNER research_app;' -Quiet }
Invoke-DatabaseSql $admin "REVOKE ALL ON DATABASE research_workbench FROM PUBLIC;`nREVOKE CONNECT ON DATABASE postgres FROM PUBLIC;`nREVOKE CONNECT ON DATABASE template1 FROM PUBLIC;" -Quiet
$verification = Invoke-DatabaseSql $app "SELECT current_database() || ':' || current_user || ':' || rolsuper || ':' || rolcreatedb || ':' || rolcreaterole FROM pg_roles WHERE rolname=current_user;"
if ($verification -ne 'research_workbench:research_app:false:false:false') { throw 'Application role verification failed; database.json was not created.' }
Write-DatabasePrivateJson $script:DatabaseConfig $app
Write-Host 'PostgreSQL verified: research_app owns only research_workbench, without superuser/create-role/create-database privileges.'
Write-Host 'Connection: 127.0.0.1:55432. Private credentials: .local/database.json (never commit).'
