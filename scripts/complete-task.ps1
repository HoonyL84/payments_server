param(
  [Parameter(Mandatory = $true)]
  [string]$TaskName,

  [switch]$Force
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

$worktreeDir = Join-Path ".worktrees" $TaskName
$planFile = Join-Path ".harness/tasks/active" "$TaskName.md"
$archiveDir = ".harness/tasks/archive"
$verifyLog = Join-Path "observability/metrics" "$TaskName.verify.json"
$startLog = Join-Path "observability/metrics" "$TaskName.start.json"
$doneLog = Join-Path "observability/metrics" "$TaskName.done.json"

Write-Host "[Harness] Task complete: $TaskName"

$verifyResult = "none"
$reworkCount = 0
$lastFailReason = "none"

if (Test-Path $verifyLog) {
  $verify = Get-Content -Raw -Encoding UTF8 $verifyLog | ConvertFrom-Json
  $verifyResult = $verify.result
  if ($null -ne $verify.rework_count) {
    $reworkCount = [int]$verify.rework_count
  }
  if ($null -ne $verify.last_fail_reason) {
    $lastFailReason = [string]$verify.last_fail_reason
  }
}

if (-not $Force -and $verifyResult -ne "pass") {
  Write-Error "Task verification is not marked as pass. Re-run verification or use -Force."
}

if (Test-Path $worktreeDir) {
  git worktree remove -f $worktreeDir
  foreach ($prefix in @("feat", "fix", "refactor", "docs", "chore", "experiment")) {
    git branch -D "$prefix/$TaskName" 2>$null
  }
  Write-Host "[Harness] Worktree removed"
}
else {
  Write-Host "[Harness] Worktree not found: $worktreeDir"
}

if (Test-Path $planFile) {
  New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
  Move-Item -LiteralPath $planFile -Destination (Join-Path $archiveDir "$TaskName.md") -Force
  Write-Host "[Harness] EXEC_PLAN archived"
}
else {
  Write-Host "[Harness] EXEC_PLAN not found: $planFile"
}

$startedAt = "unknown"
$taskType = "unknown"
$project = "unknown"

if (Test-Path $startLog) {
  $start = Get-Content -Raw -Encoding UTF8 $startLog | ConvertFrom-Json
  if ($null -ne $start.started_at) {
    $startedAt = [string]$start.started_at
  }
  if ($null -ne $start.type) {
    $taskType = [string]$start.type
  }
  if ($null -ne $start.project) {
    $project = [string]$start.project
  }
}

New-Item -ItemType Directory -Force -Path "observability/metrics" | Out-Null
$done = [ordered]@{
  task = $TaskName
  type = $taskType
  project = $project
  started_at = $startedAt
  completed_at = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
  rework_count = $reworkCount
  last_fail_reason = $lastFailReason
  verify_result = $verifyResult
}
$done | ConvertTo-Json | Set-Content -Encoding UTF8 $doneLog

Remove-Item -Force -ErrorAction SilentlyContinue $startLog, $verifyLog

Write-Host "[Harness] Done metric written: $doneLog"
Write-Host "[Harness] Task complete."
