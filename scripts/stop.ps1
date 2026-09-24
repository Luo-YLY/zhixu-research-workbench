[CmdletBinding()]
param([switch]$WithDatabase)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $projectRoot '.local/app.json'
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $jarPath = Join-Path $projectRoot 'backend/target/research-workbench-backend-0.1.0.jar'
    $process = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
    if ($process) {
        if (-not $state.processStartTicks -or -not $state.executablePath -or
            [string]$process.StartTime.ToUniversalTime().Ticks -ne [string]$state.processStartTicks -or
            $process.Path -ne $state.executablePath -or $state.jar -ne $jarPath) {
            throw 'Saved PID does not identify this application; refusing to stop it.'
        }
        # The verified Java process owns ephemeral Codex children; stop the entire owned tree.
        # Pending model turns are marked INTERRUPTED on next startup, never replayed.
        & taskkill.exe /PID $state.pid /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the managed application process tree.' }
        Wait-Process -Id $state.pid -Timeout 20 -ErrorAction SilentlyContinue
        Write-Host 'Research Workbench stopped. Persisted data is retained.'
    }
    Remove-Item -LiteralPath $statePath -Force
} else { Write-Host 'No managed application process.' }
if ($WithDatabase) { & (Join-Path $PSScriptRoot 'database-stop.ps1') }
