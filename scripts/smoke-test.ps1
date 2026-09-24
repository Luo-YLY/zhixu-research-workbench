[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:18081')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Request-Json([string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = @{}) {
    $arguments = @{Method=$Method; Uri=($BaseUrl+$Path); Headers=$Headers; TimeoutSec=10}
    if ($null -ne $Body) { $arguments.ContentType='application/json; charset=utf-8'; $arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress)) }
    Invoke-RestMethod @arguments
}
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw "Assertion failed: $Message" } }
function Wait-Run([string]$RunId, [string[]]$Wanted) {
    for ($i=0; $i -lt 100; $i++) {
        $result = Request-Json 'GET' "/api/runs/$RunId"
        if ($result.status -in $Wanted) { return $result }
        if ($result.status -in @('FAILED','CANCELLED','REJECTED','COMPLETED')) { throw "Unexpected terminal status: $($result.status)" }
        Start-Sleep -Milliseconds 200
    }
    throw "Run $RunId did not reach $($Wanted -join ',')."
}
function Assert-Status([int]$Expected, [string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = @{}) {
    $actual=0
    try { $null=Request-Json $Method $Path $Body $Headers } catch {
        if ($_.Exception.Response) { $actual=[int]$_.Exception.Response.StatusCode } else { throw }
    }
    Assert-True ($actual -eq $Expected) "Expected HTTP $Expected for $Method $Path, got $actual"
}

$system = Request-Json 'GET' '/api/system'
Assert-True ($system.executionMode -eq 'DEMO') 'Explicit DEMO execution mode'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$project = Request-Json 'POST' '/api/projects' @{name="验收记录 $stamp"; description='自动验收产生的示例项目，不含真实研究结论。'}
$task = Request-Json 'POST' '/api/tasks' @{projectId=$project.id; title='批准与归档验收'; objective='验证持久化任务、人工审批、产物哈希和重复请求处理。'}
$key = [Guid]::NewGuid().ToString()
$run = Request-Json 'POST' "/api/tasks/$($task.id)/runs" @{} @{'Idempotency-Key'=$key}
$repeat = Request-Json 'POST' "/api/tasks/$($task.id)/runs" @{} @{'Idempotency-Key'=$key}
Assert-True ($run.id -eq $repeat.id) 'Idempotent launch returns same run'
Assert-Status 409 'POST' "/api/tasks/$($task.id)/runs" @{}
$waiting = Wait-Run $run.id @('WAITING_APPROVAL')
Assert-True ($waiting.nodes.Count -eq 5) 'Five durable workflow nodes'
Assert-True ($waiting.artifacts.Count -eq 0) 'No final artifact before approval'
Assert-True ($waiting.approval.status -eq 'PENDING') 'Pending approval recorded'
$null = Request-Json 'POST' "/api/approvals/$($waiting.approval.id)/decisions" @{decision='APPROVE'; comment='基础流程验收通过，不代表真实研究有效。'}
$complete = Wait-Run $run.id @('COMPLETED')
Assert-True ($complete.artifacts.Count -gt 0) 'Archived artifact available'
Assert-Status 409 'POST' "/api/approvals/$($waiting.approval.id)/decisions" @{decision='APPROVE'; comment='重复提交'}
Assert-Status 409 'POST' "/api/runs/$($run.id)/cancel" @{}
$artifact = $complete.artifacts[0]
$localPath = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Path $localPath -Force | Out-Null
$downloadPath = Join-Path $localPath 'smoke-artifact.json'
Invoke-WebRequest -Uri ($BaseUrl + $artifact.downloadUrl) -OutFile $downloadPath -UseBasicParsing -TimeoutSec 10
$actualHash = (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-True ($actualHash -eq $artifact.sha256.ToLowerInvariant()) 'Artifact SHA-256 matches downloaded bytes'
Assert-True ((Get-Item -LiteralPath $downloadPath).Length -eq $artifact.sizeBytes) 'Artifact size matches'
Assert-True ((Get-Content -LiteralPath $downloadPath -Raw -Encoding UTF8) -match 'DEMO') 'Artifact marked DEMO'

$rejectTask = Request-Json 'POST' '/api/tasks' @{projectId=$project.id; title='退回验收'; objective='验证退回原因与终态。'}
$rejectRun = Request-Json 'POST' "/api/tasks/$($rejectTask.id)/runs" @{}
$rejectWait = Wait-Run $rejectRun.id @('WAITING_APPROVAL')
Assert-Status 400 'POST' "/api/approvals/$($rejectWait.approval.id)/decisions" @{decision='REJECT'; comment=''}
$rejected = Request-Json 'POST' "/api/approvals/$($rejectWait.approval.id)/decisions" @{decision='REJECT'; comment='验收用例：研究输入需要补充。'}
Assert-True ($rejected.status -eq 'REJECTED') 'Rejected run is terminal'
Assert-True ($rejected.artifacts.Count -eq 0) 'Rejected run has no final artifact'

$cancelTask = Request-Json 'POST' '/api/tasks' @{projectId=$project.id; title='取消验收'; objective='验证人工关卡等待时取消，不能继续审批。'}
$cancelRun = Request-Json 'POST' "/api/tasks/$($cancelTask.id)/runs" @{}
$cancelWait = Wait-Run $cancelRun.id @('WAITING_APPROVAL')
$cancelled = Request-Json 'POST' "/api/runs/$($cancelRun.id)/cancel" @{}
Assert-True ($cancelled.status -eq 'CANCELLED') 'Cancelled run is terminal'
Assert-Status 409 'POST' "/api/approvals/$($cancelWait.approval.id)/decisions" @{decision='APPROVE'; comment='取消后审批'}
Assert-Status 400 'POST' '/api/projects' @{name=''; description=''}
Assert-Status 403 'POST' '/api/projects' @{name='cross origin'; description=''} @{Origin='https://unrelated.example'}
Assert-Status 404 'GET' ('/api/runs/' + [Guid]::NewGuid().ToString())

$dashboard = Request-Json 'GET' '/api/dashboard'
$result = @{verifiedAt=[DateTime]::UtcNow.ToString('o'); database=$system.database; projectId=$project.id; completedRunId=$run.id; rejectedRunId=$rejectRun.id; cancelledRunId=$cancelRun.id; artifactSha256=$actualHash; dashboard=$dashboard; checks='create, idempotency, duplicate active run, human gate, approve, reject, cancel, invalid transitions, artifact bytes/hash, validation, cross-origin rejection, not-found'}
$resultPath = Join-Path $localPath 'smoke-result.json'
[IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Host "HTTP smoke test passed ($($system.database)). Evidence: .local/smoke-result.json"
