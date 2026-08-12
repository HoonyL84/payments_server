param(
    [int[]]$Rates = @(10, 50, 100, 200, 400),
    [string[]]$Scenarios = @("normal", "pg-delay", "merchant-skew"),
    [int]$Repetitions = 3,
    [string]$WarmupDuration = "5s",
    [string]$MeasuredDuration = "15s",
    [string]$EvidenceDirectory = "docs/history/evidence/step20",
    [string]$ReportPath = "docs/history/evidence/step20-capacity.json",
    [switch]$Quick
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
New-Item -ItemType Directory -Force (Join-Path $repo $EvidenceDirectory) | Out-Null
New-Item -ItemType Directory -Force (Split-Path -Parent (Join-Path $repo $ReportPath)) | Out-Null
$base = "http://localhost:8085"
$prometheus = "http://localhost:9090"
$measuredSeconds = [int]$MeasuredDuration.TrimEnd("s")
$warmupSeconds = [int]$WarmupDuration.TrimEnd("s")
if ($Quick) {
    $Rates = @(10, 100)
    $Scenarios = @("normal", "pg-delay")
    $Repetitions = 1
    $WarmupDuration = "2s"
    $MeasuredDuration = "5s"
    $measuredSeconds = 5
    $warmupSeconds = 2
}

function Wait-Http {
    param([string]$Uri, [int]$Seconds = 120)
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try {
            Invoke-RestMethod -Uri $Uri -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 1
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri"
}

function Metric-Value {
    param($Summary, [string]$Metric, [string]$Value, [double]$Default = 0)
    $entry = $Summary.metrics.PSObject.Properties[$Metric]
    if ($null -eq $entry) { return $Default }
    $values = if ($null -eq $entry.Value.values) { $entry.Value } else { $entry.Value.values }
    $metricValue = $values.PSObject.Properties[$Value]
    if ($null -eq $metricValue) { return $Default }
    return [double]$metricValue.Value
}

function Query-Prometheus {
    param([string]$Query)
    try {
        $encoded = [uri]::EscapeDataString($Query)
        $response = Invoke-RestMethod -Uri "$prometheus/api/v1/query?query=$encoded" -TimeoutSec 10
        if ($response.status -ne "success" -or $response.data.result.Count -eq 0) { return $null }
        return [double]$response.data.result[0].value[1]
    } catch {
        return $null
    }
}

function Get-KafkaLag {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $lines = docker exec harness-kafka kafka-consumer-groups --bootstrap-server kafka:29092 --describe --group payment-audit-v1 2>$null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($exitCode -ne 0) { return $null }
    $total = 0L
    foreach ($line in $lines) {
        if ($line -match "payments\.capacity\.v1\s+\d+\s+\d+\s+\d+\s+(\d+)") {
            $total += [long]$Matches[1]
        }
    }
    return $total
}

function Get-Consistency {
    Invoke-RestMethod -Uri "$base/internal/v1/test-support/consistency" -TimeoutSec 15
}

function Test-Saturated {
    param($Run, [double]$LatencyBudget)
    return $Run.unexpectedCount -gt 0 -or $Run.shedRate -ge 0.01 -or $Run.droppedIterations -gt 0 -or $Run.p99Millis -gt $LatencyBudget
}

function Get-ScenarioDecision {
    param([string]$Scenario, $Runs)
    $budget = if ($Scenario -eq "pg-delay") { 700.0 } else { 500.0 }
    $levels = @()
    foreach ($rate in $Rates) {
        $atRate = @($Runs | Where-Object { $_.scenario -eq $Scenario -and $_.offeredRate -eq $rate })
        $saturatedRuns = @($atRate | Where-Object { Test-Saturated $_ $budget }).Count
        $orderedP99 = @($atRate.p99Millis | Sort-Object)
        $medianP99 = if ($orderedP99.Count -eq 0) { $null } else { $orderedP99[[math]::Floor($orderedP99.Count / 2)] }
        $levels += [ordered]@{
            offeredRate = $rate
            repetitions = $atRate.Count
            saturatedRuns = $saturatedRuns
            medianThroughput = if ($atRate.Count -eq 0) { 0 } else { ($atRate.throughput | Measure-Object -Average).Average }
            medianP99Millis = $medianP99
            maxShedRate = if ($atRate.Count -eq 0) { 0 } else { ($atRate.shedRate | Measure-Object -Maximum).Maximum }
            maxHikariActive = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.hikariActive | Measure-Object -Maximum).Maximum }
            maxHikariPending = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.hikariPending | Measure-Object -Maximum).Maximum }
            maxCommandActive = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.commandActive | Measure-Object -Maximum).Maximum }
            maxCommandQueued = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.commandQueued | Measure-Object -Maximum).Maximum }
            maxProcessCpu = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.processCpu | Measure-Object -Maximum).Maximum }
            maxHeapBytes = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.heapBytes | Measure-Object -Maximum).Maximum }
            maxRedisGateMillis = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.redisGateMaxMillis | Measure-Object -Maximum).Maximum }
            maxRedisUnavailable = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.redisUnavailable | Measure-Object -Maximum).Maximum }
            maxOutboxPublishLagSeconds = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.outboxPublishLagMaxSeconds | Measure-Object -Maximum).Maximum }
            maxPendingOutbox = if ($atRate.Count -eq 0) { 0 } else { ($atRate.consistency.pendingOutbox | Measure-Object -Maximum).Maximum }
            maxKafkaConsumerLag = if ($atRate.Count -eq 0) { 0 } else { ($atRate.resources.kafkaConsumerLag | Measure-Object -Maximum).Maximum }
            meanMerchantOneRatio = if ($Scenario -ne "merchant-skew" -or $atRate.Count -eq 0) { $null } else { ($atRate.merchantDistribution.merchantOneRatio | Measure-Object -Average).Average }
        }
    }
    $requiredFailures = [math]::Ceiling($Repetitions / 2.0)
    $firstSaturated = $levels | Where-Object { $_.saturatedRuns -ge $requiredFailures } | Select-Object -First 1
    $safe = $levels | Where-Object { $null -eq $firstSaturated -or $_.offeredRate -lt $firstSaturated.offeredRate } | Select-Object -Last 1
    return [ordered]@{
        scenario = $Scenario
        latencyBudgetMillis = $budget
        safeOfferedRate = if ($null -eq $safe) { $null } else { $safe.offeredRate }
        firstSaturatedRate = if ($null -eq $firstSaturated) { $null } else { $firstSaturated.offeredRate }
        testedCeiling = ($Rates | Measure-Object -Maximum).Maximum
        levels = $levels
    }
}

Push-Location $repo
try {
    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    docker compose --profile capacity up -d --build capacity-mysql capacity-redis zookeeper kafka mock-pg-capacity app-capacity payment-consumer-capacity prometheus
    if ($LASTEXITCODE -ne 0) { throw "Failed to start Step 20 services." }
    docker compose restart prometheus
    if ($LASTEXITCODE -ne 0) { throw "Failed to reload Prometheus configuration." }
    Wait-Http "$base/actuator/health"
    Wait-Http "$prometheus/-/ready"

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker pull grafana/k6:0.54.0 2>&1 | Write-Host
    $pullExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($pullExitCode -ne 0) { throw "Failed to pull the pinned k6 image." }

    $gitSha = (git rev-parse HEAD).Trim()
    $appImage = (docker image inspect payments-service:local --format "{{.Id}}").Trim()
    $consumerImage = (docker image inspect payment-event-consumer:local --format "{{.Id}}").Trim()
    $dockerInfo = docker info --format "{{json .}}" | ConvertFrom-Json
    $runs = @()

    foreach ($scenario in $Scenarios) {
        foreach ($rate in $Rates) {
            for ($repeat = 1; $repeat -le $Repetitions; $repeat++) {
                $fileName = "$scenario-rate-$rate-run-$repeat.json"
                $relativeSummary = "$EvidenceDirectory/$fileName" -replace "\\", "/"
                Write-Host "Step 20: scenario=$scenario rate=$rate repeat=$repeat"
                $runStarted = (Get-Date).ToUniversalTime()
                $ErrorActionPreference = "Continue"
                & docker run --rm -e BASE_URL=http://host.docker.internal:8085 -e MOCK_PG_URL=http://host.docker.internal:8093 -e CAPACITY_SCENARIO=$scenario -e RATE=$rate -e WARMUP_DURATION=$WarmupDuration -e MEASURED_DURATION=$MeasuredDuration -v "$($repo):/work" grafana/k6:0.54.0 run --summary-export "/work/$relativeSummary" /work/load-tests/k6/capacity-saturation.js 2>&1 | Write-Host
                $k6ExitCode = $LASTEXITCODE
                $ErrorActionPreference = $previousErrorAction
                if ($k6ExitCode -ne 0) { throw "k6 failed for scenario=$scenario rate=$rate repeat=$repeat" }
                Start-Sleep -Seconds 2

                $summary = Get-Content -Raw -Encoding utf8 $relativeSummary | ConvertFrom-Json
                $acceptedCount = Metric-Value $summary "capacity_accepted" "count"
                $shedCount = Metric-Value $summary "capacity_shed" "count"
                $unexpectedCount = Metric-Value $summary "capacity_unexpected" "count"
                $merchantOneCount = Metric-Value $summary "capacity_merchant_1" "count"
                $merchantTwoCount = Metric-Value $summary "capacity_merchant_2" "count"
                $measuredRequests = $acceptedCount + $shedCount + $unexpectedCount
                $windowSeconds = $warmupSeconds + $measuredSeconds + 15
                $consistency = Get-Consistency
                $resources = [ordered]@{
                    processCpu = Query-Prometheus "max(max_over_time(process_cpu_usage{job='payments-capacity'}[$($windowSeconds)s]))"
                    heapBytes = Query-Prometheus "max(max_over_time(jvm_memory_used_bytes{job='payments-capacity',area='heap'}[$($windowSeconds)s]))"
                    hikariActive = Query-Prometheus "max(max_over_time(hikaricp_connections_active{job='payments-capacity'}[$($windowSeconds)s]))"
                    hikariPending = Query-Prometheus "max(max_over_time(hikaricp_connections_pending{job='payments-capacity'}[$($windowSeconds)s]))"
                    commandActive = Query-Prometheus "max(max_over_time(payments_provider_executor_active{job='payments-capacity',workload='command'}[$($windowSeconds)s]))"
                    commandQueued = Query-Prometheus "max(max_over_time(payments_provider_executor_queued{job='payments-capacity',workload='command'}[$($windowSeconds)s]))"
                    redisGateMaxMillis = 1000 * (Query-Prometheus "max(max_over_time(payments_idempotency_gate_duration_seconds_max{job='payments-capacity'}[$($windowSeconds)s]))")
                    redisUnavailable = Query-Prometheus "sum(increase(payments_idempotency_gate_total{job='payments-capacity',outcome='unavailable'}[$($windowSeconds)s]))"
                    outboxPublishLagMaxSeconds = Query-Prometheus "max(max_over_time(payments_outbox_publish_lag_seconds_max{job='payments-capacity'}[$($windowSeconds)s]))"
                    kafkaConsumerLag = Get-KafkaLag
                }
                $runs += [pscustomobject][ordered]@{
                    scenario = $scenario
                    offeredRate = $rate
                    repetition = $repeat
                    startedAt = $runStarted.ToString("o")
                    throughput = [math]::Round($acceptedCount / $measuredSeconds, 3)
                    acceptedCount = $acceptedCount
                    shedCount = $shedCount
                    shedRate = if ($measuredRequests -eq 0) { 0 } else { [math]::Round($shedCount / $measuredRequests, 6) }
                    merchantDistribution = [ordered]@{
                        merchantOne = $merchantOneCount
                        merchantTwo = $merchantTwoCount
                        merchantOneRatio = if (($merchantOneCount + $merchantTwoCount) -eq 0) { 0 } else { [math]::Round($merchantOneCount / ($merchantOneCount + $merchantTwoCount), 6) }
                    }
                    unexpectedCount = $unexpectedCount
                    unexpectedStatuses = [ordered]@{
                        connectionFailure = Metric-Value $summary "capacity_status_0" "count"
                        conflict = Metric-Value $summary "capacity_status_409" "count"
                        serverError = Metric-Value $summary "capacity_status_5xx" "count"
                        other = Metric-Value $summary "capacity_status_other" "count"
                    }
                    droppedIterations = Metric-Value $summary "dropped_iterations" "count"
                    p95Millis = Metric-Value $summary "capacity_approval_duration" "p(95)"
                    p99Millis = Metric-Value $summary "capacity_approval_duration" "p(99)"
                    resources = $resources
                    consistency = $consistency
                    summaryFile = $relativeSummary
                }
            }
        }
    }

    $decisions = @()
    foreach ($scenario in $Scenarios) {
        $decisions += Get-ScenarioDecision $scenario $runs
    }
    $foundSaturation = @($decisions | Where-Object { $null -ne $_.firstSaturatedRate }).Count -gt 0
    $report = [ordered]@{
        step = 20
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        gitSha = $gitSha
        images = [ordered]@{ app = $appImage; consumer = $consumerImage; k6 = "grafana/k6:0.54.0" }
        fixedConditions = [ordered]@{
            rates = $Rates
            scenarios = $Scenarios
            repetitions = $Repetitions
            warmupDuration = $WarmupDuration
            measuredDuration = $MeasuredDuration
            hikariMaximumPoolSize = 10
            commandMaxConcurrent = 10
            inquiryMaxConcurrent = 2
            commandTimeout = "PT0.5S"
            dockerCpus = $dockerInfo.NCPU
            dockerMemoryBytes = $dockerInfo.MemTotal
            explicitContainerCpuMemoryLimit = $false
        }
        decisionRule = "At least half of repeats breach p99 budget, 1% shedding, dropped iterations, or unexpected errors."
        decisions = $decisions
        runs = $runs
        result = if ($foundSaturation) { "PASS" } else { "INCONCLUSIVE_NO_SATURATION" }
        limitation = "Local Docker result; not an operating SLO or production maximum TPS."
    }
    $report | ConvertTo-Json -Depth 15 | Set-Content -Encoding utf8 $ReportPath
    if (-not $foundSaturation) { throw "No saturation point found up to the tested ceiling." }
    Write-Host "Step 20 Acceptance Result: PASS"
    Write-Host "Evidence: $ReportPath"
} finally {
    Pop-Location
}
