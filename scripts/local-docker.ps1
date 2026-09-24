[CmdletBinding()]
param([ValidateSet('up','down','status','config','models-up','models-down','models-status','models-config','runner-config','runner-up','runner-status','llama-config','llama-up','llama-status','llama-down')][string]$Action = 'status')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $projectRoot '.local'
$secretPath = Join-Path $localRoot 'docker-db-password'
$baseCompose = Join-Path $projectRoot 'deploy/compose.yaml'
$localCompose = Join-Path $projectRoot 'deploy/compose.local.yaml'
$modelCompose = Join-Path $projectRoot 'deploy/compose.models.local.yaml'
$runnerCompose = Join-Path $projectRoot 'deploy/compose.model-runner.local.yaml'
$llamaCompose = Join-Path $projectRoot 'deploy/compose.llama.local.yaml'
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else {
    Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'
}
if (-not (Test-Path -LiteralPath $docker -PathType Leaf)) { throw 'Docker CLI is not installed.' }

if ($Action -in @('up','config','models-up','models-config','runner-up','runner-config','llama-up','llama-config')) {
    New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
    if (-not (Test-Path -LiteralPath $secretPath)) {
        $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
        $password = [Convert]::ToHexString($bytes).ToLowerInvariant()
        [IO.File]::WriteAllText($secretPath, $password, [Text.UTF8Encoding]::new($false))
    }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $actual = @(Get-Acl -LiteralPath $secretPath).Access
    $restricted = $actual.Count -eq 1 -and $actual[0].IdentityReference.Value -eq $identity `
        -and $actual[0].AccessControlType -eq 'Allow' -and -not $actual[0].IsInherited
    if (-not $restricted) {
        $acl = [Security.AccessControl.FileSecurity]::new()
        $acl.SetAccessRuleProtection($true, $false)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'Allow'))
        Set-Acl -LiteralPath $secretPath -AclObject $acl
        $actual = @(Get-Acl -LiteralPath $secretPath).Access
    }
    if ($actual.Count -ne 1 -or $actual[0].IdentityReference.Value -ne $identity -or $actual[0].AccessControlType -ne 'Allow') {
        throw 'Local PostgreSQL password file permissions are not restricted to the current user.'
    }
}

$compose = @('compose', '-f', $baseCompose, '-f', $localCompose)
$modelStack = @('compose', '-f', $baseCompose, '-f', $localCompose, '-f', $modelCompose)
$runnerStack = @('compose', '-f', $baseCompose, '-f', $localCompose, '-f', $runnerCompose)
$llamaStack = @('compose', '-f', $baseCompose, '-f', $localCompose, '-f', $llamaCompose)
$previousPath = $env:PATH
try {
    # The Desktop credential helper lives beside docker.exe and may be absent
    # from PowerShell sessions that were opened before Docker was installed.
    $env:PATH = (Split-Path -Parent $docker) + [IO.Path]::PathSeparator + $previousPath
    switch ($Action) {
        'up' {
            & $docker @compose config -q
            if ($LASTEXITCODE -ne 0) { throw 'Local Compose configuration is invalid.' }
            & $docker @compose up -d --build
        }
        'down' { & $docker @compose down }
        'status' { & $docker @compose ps }
        'config' { & $docker @compose config -q }
        'models-config' { & $docker @modelStack config -q }
        'models-status' { & $docker @modelStack ps }
        'models-down' { & $docker @modelStack down }
        'runner-config' { & $docker @runnerStack config -q }
        'llama-config' { & $docker @llamaStack config -q }
        'llama-status' { & $docker @llamaStack ps }
        'llama-down' { & $docker @llamaStack down }
        'llama-up' {
            & $docker @llamaStack config -q
            if ($LASTEXITCODE -ne 0) { throw 'Local llama.cpp Compose configuration is invalid.' }
            $modelFiles = @(
                @{ Name='bge-m3-q8_0.gguf'; Hash='aa473d51f451a22f0fcf39ba3330c14bed38a385712b1113440f69df4047a173' },
                @{ Name='qwen3-4b-q4_k_m.gguf'; Hash='7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5' }
            )
            foreach ($modelFile in $modelFiles) {
                $path = Join-Path $localRoot ('models/' + $modelFile.Name)
                if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing local model file: $($modelFile.Name)" }
                $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
                if ($hash -ne $modelFile.Hash) { throw "Local model hash mismatch: $($modelFile.Name)" }
            }
            & $docker @llamaStack up -d --build
        }
        'runner-status' {
            & $docker model status
            if ($LASTEXITCODE -ne 0) { throw 'Docker Model Runner is not ready.' }
            & $docker model list
            if ($LASTEXITCODE -ne 0) { throw 'Cannot list local models.' }
            & $docker @runnerStack ps
        }
        'runner-up' {
            & $docker @runnerStack config -q
            if ($LASTEXITCODE -ne 0) { throw 'Local Model Runner Compose configuration is invalid.' }
            & $docker model status
            if ($LASTEXITCODE -ne 0) { throw 'Enable Docker Model Runner in Docker Desktop Settings > AI first.' }
            & $docker model pull 'hf.co/ggml-org/bge-m3-Q8_0-GGUF:Q8_0'
            if ($LASTEXITCODE -ne 0) { throw 'Embedding model download failed; retry runner-up after network recovers.' }
            & $docker model pull 'hf.co/Qwen/Qwen3-4B-GGUF:Q4_K_M'
            if ($LASTEXITCODE -ne 0) { throw 'Answer model download failed; retry runner-up after network recovers.' }
            & $docker @runnerStack up -d --build app db
        }
        'models-up' {
            & $docker @modelStack config -q
            if ($LASTEXITCODE -ne 0) { throw 'Local model Compose configuration is invalid.' }
            & $docker @modelStack up -d model
            if ($LASTEXITCODE -ne 0) { throw 'Local model container failed to start.' }
            & $docker @modelStack exec -T model ollama pull bge-m3
            if ($LASTEXITCODE -ne 0) { throw 'Embedding model download failed.' }
            & $docker @modelStack exec -T model ollama pull qwen3:4b
            if ($LASTEXITCODE -ne 0) { throw 'Answer model download failed.' }
            & $docker @modelStack up -d --build app db
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "Local Docker action failed: $Action" }
} finally {
    $env:PATH = $previousPath
}
