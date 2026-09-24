$ErrorActionPreference = 'Stop'
$script:DatabaseProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:DatabaseLocalRoot = Join-Path $script:DatabaseProjectRoot '.local'
$script:DatabaseBinaryRoot = Join-Path $script:DatabaseLocalRoot 'postgres-binaries'
$script:DatabaseBin = Join-Path $script:DatabaseBinaryRoot 'pgsql/bin'
$script:DatabaseData = Join-Path $script:DatabaseLocalRoot 'postgres-data'
$script:DatabaseConfig = Join-Path $script:DatabaseLocalRoot 'database.json'
$script:DatabaseAdminConfig = Join-Path $script:DatabaseLocalRoot 'postgres-admin.json'
$script:DatabasePort = 55432

function Assert-DatabaseLocalPath([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    $boundary = $script:DatabaseLocalRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Database path must remain inside project .local: $resolved"
    }
    return $resolved
}

function Get-DatabaseExecutable([string]$Name) {
    $path = Assert-DatabaseLocalPath (Join-Path $script:DatabaseBin ($Name + '.exe'))
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'Local PostgreSQL is not installed. Run scripts/database-setup.ps1 with an official EDB Windows x64 ZIP, or use the H2 profile.'
    }
    return $path
}

function New-DatabasePassword {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Write-DatabasePrivateJson([string]$Path, $Value) {
    $safePath = Assert-DatabaseLocalPath $Path
    [IO.File]::WriteAllText($safePath, ($Value | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    # Credentials stay in ignored .local and are readable only by the current Windows identity.
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $safePath -AclObject $acl
}

function Invoke-DatabaseSql($Config, [string]$Sql, [switch]$Quiet) {
    $psql = Get-DatabaseExecutable 'psql'
    $priorPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
    $priorEncoding = [Environment]::GetEnvironmentVariable('PGCLIENTENCODING', 'Process')
    try {
        $env:PGPASSWORD = $Config.password
        $env:PGCLIENTENCODING = 'UTF8'
        # Password-bearing SQL is provided over stdin, never on the process command line.
        $result = $Sql | & $psql -X -w -v ON_ERROR_STOP=1 -h $Config.host -p $Config.port -U $Config.username -d $Config.database -t -A 2>&1
        if ($LASTEXITCODE -ne 0) {
            # Do not repeat psql input or output here: role-creation input may contain passwords.
            throw 'PostgreSQL command failed. Check connection settings and the private local database log.'
        }
        if (-not $Quiet) { return ($result -join "`n").Trim() }
    } finally {
        [Environment]::SetEnvironmentVariable('PGPASSWORD', $priorPassword, 'Process')
        [Environment]::SetEnvironmentVariable('PGCLIENTENCODING', $priorEncoding, 'Process')
    }
}

function Test-DatabaseRunning {
    if (-not (Test-Path -LiteralPath (Join-Path $script:DatabaseData 'PG_VERSION'))) { return $false }
    $pgCtl = Get-DatabaseExecutable 'pg_ctl'
    & $pgCtl status -D $script:DatabaseData *> $null
    return ($LASTEXITCODE -eq 0)
}

function Start-ProjectDatabase {
    $pgCtl = Get-DatabaseExecutable 'pg_ctl'
    if (-not (Test-Path -LiteralPath (Join-Path $script:DatabaseData 'PG_VERSION'))) {
        throw 'Local PostgreSQL data is not initialized. Run database-setup.ps1 first.'
    }
    if (Test-DatabaseRunning) { return }
    $stdout = Join-Path $script:DatabaseLocalRoot 'postgres-start.stdout.log'
    $stderr = Join-Path $script:DatabaseLocalRoot 'postgres-start.stderr.log'
    # Relative data/log paths avoid passing the Unicode project path to PostgreSQL child arguments.
    $process = Start-Process -FilePath $pgCtl -ArgumentList @('start', '-D', 'postgres-data', '-l', 'postgres-server.log', '-w', '-t', '30') -WorkingDirectory $script:DatabaseLocalRoot -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if ($process.ExitCode -ne 0) { throw 'Local PostgreSQL could not start. See .local/postgres-start.stderr.log and postgres-server.log.' }
}
