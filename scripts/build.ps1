[CmdletBinding()]
param([switch]$SkipInstall, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
$mavenCommand = (Get-Command mvn.cmd -ErrorAction Stop).Source

Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not $SkipInstall) {
        & $npmCommand ci --cache (Join-Path $projectRoot '.local/npm-cache') --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    if (-not $SkipTests) {
        & $npmCommand test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend state tests failed.' }
    }
    & $npmCommand run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }

$staticPath = Join-Path $projectRoot 'backend/src/main/resources/static'
New-Item -ItemType Directory -Path $staticPath -Force | Out-Null
# Only delete generated frontend assets within this project's static directory.
$resolvedStatic = [IO.Path]::GetFullPath($staticPath)
$expectedStatic = [IO.Path]::GetFullPath((Join-Path $projectRoot 'backend/src/main/resources/static'))
if ($resolvedStatic -ne $expectedStatic) { throw 'Unexpected generated asset path.' }
Get-ChildItem -LiteralPath $resolvedStatic -Force | Remove-Item -Recurse -Force
Copy-Item -Path (Join-Path $projectRoot 'frontend/dist/*') -Destination $staticPath -Recurse -Force

Push-Location (Join-Path $projectRoot 'backend')
try {
    $mavenArgs = @('-B', '-ntp', ('-Dmaven.repo.local=' + (Join-Path $projectRoot '.local/m2')), 'package')
    if ($SkipTests) { $mavenArgs += '-DskipTests' }
    & $mavenCommand @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
} finally { Pop-Location }
Write-Host 'Built backend/target/research-workbench-backend-0.1.0.jar (frontend included).'
