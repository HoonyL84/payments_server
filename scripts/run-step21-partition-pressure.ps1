param(
    [int]$Repetitions = 3,
    [string]$WarmupDuration = "3s",
    [string]$MeasuredDuration = "10s",
    [string]$EvidenceDirectory = "docs/history/evidence/step21",
    [string]$ReportPath = "docs/history/evidence/step21-partition-pressure.json",
    [switch]$Quick
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$base = "http://localhost:8085"
$mockPg = "http://localhost:8093"
$prometheus = "http://localhost:9090"
$topic = "payments.capacity.v1"
$consumerGroup = "payment-audit-v1"
$profiles = @("uniform", "merchant-80-20", "single-hot")
$rates = @(100, 200)
$measuredSeconds = [int]$MeasuredDuration.TrimEnd("s")
if ($Quick) {
    $profiles = @("uniform")
    $rates = @(100)
    $Repetitions = 1
    $WarmupDuration = "2s"
    $MeasuredDuration = "5s"
    $measuredSeconds = 5
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
        if ($response.status -ne "success" -or $response.data.result.Count -eq 0) { return 0 }
        return [double]$response.data.result[0].value[1]
    } catch {
        return 0
    }
}

function Get-Consistency {
    Invoke-RestMethod -Uri "$base/internal/v1/test-support/consistency" -TimeoutSec 15
}

function Get-KafkaOffsets {
    $lines = docker exec harness-kafka kafka-get-offsets --bootstrap-server kafka:29092 --topic $topic --time -1
    if ($LASTEXITCODE -ne 0) { throw "Failed to read Kafka offsets." }
    $offsets = [ordered]@{}
    foreach ($line in $lines) {
        if ($line -match "^$([regex]::Escape($topic)):(\d+):(\d+)$") {
            $offsets[$Matches[1]] = [long]$Matches[2]
        }
    }
    return [pscustomobject]$offsets
}

function Get-KafkaDelta {
    param($Before, $After)
    $counts = @()
    foreach ($property in $After.PSObject.Properties | Sort-Object { [int]$_.Name }) {
        $beforeProperty = $Before.PSObject.Properties[$property.Name]
        $beforeValue = if ($null -eq $beforeProperty) { 0 } else { [long]$beforeProperty.Value }
        $counts += ([long]$property.Value - $beforeValue)
    }
    $total = ($counts | Measure-Object -Sum).Sum
    $mean = if ($counts.Count -eq 0) { 0 } else { $total / $counts.Count }
    $maximum = if ($counts.Count -eq 0) { 0 } else { ($counts | Measure-Object -Maximum).Maximum }
    return [ordered]@{
        counts = $counts
        total = $total
        maxToMeanRatio = if ($mean -eq 0) { 0 } else { [math]::Round($maximum / $mean, 6) }
        hotPartitionShare = if ($total -eq 0) { 0 } else { [math]::Round($maximum / $total, 6) }
    }
}

function Get-KafkaLag {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $lines = docker exec harness-kafka kafka-consumer-groups --bootstrap-server kafka:29092 --describe --group $consumerGroup 2>$null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($exitCode -ne 0) { return 0 }
    $total = 0L
    foreach ($line in $lines) {
        if ($line -match "$([regex]::Escape($topic))\s+\d+\s+\d+\s+\d+\s+(\d+)") {
            $total += [long]$Matches[1]
        }
    }
    return $total
}

function Wait-Convergence {
    param([int]$Seconds = 90)
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $report = Get-Consistency
        $lag = Get-KafkaLag
        if ($report.pendingOutbox -eq 0 -and
            $report.processingIdempotency -eq 0 -and
            $report.pendingConfirmations -eq 0 -and
            $report.processedEvents -eq $report.paymentEventEffects -and
            $lag -eq 0) {
            return [ordered]@{ consistency = $report; kafkaLag = $lag }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Step 21 data did not converge before timeout."
}

function Invoke-Run {
    param([string]$Profile, [int]$Rate, [int]$Repeat, [bool]$Burst)
    $name = if ($Burst) { "burst-rate-$Rate-run-$Repeat" } else { "$Profile-rate-$Rate-run-$Repeat" }
    $summaryPath = "$EvidenceDirectory/$name.json" -replace "\\", "/"
    $beforeOffsets = Get-KafkaOffsets
    $startedAt = (Get-Date).ToUniversalTime()
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & docker run --rm -e BASE_URL=http://host.docker.internal:8085 -e MOCK_PG_URL=http://host.docker.internal:8093 -e PRESSURE_PROFILE=$Profile -e RATE=$Rate -e BURST_RATE=400 -e WARMUP_DURATION=$WarmupDuration -e MEASURED_DURATION=$MeasuredDuration -v "$($repo):/work" grafana/k6:0.54.0 run --summary-export "/work/$summaryPath" /work/load-tests/k6/partition-pressure.js 2>&1 | Write-Host
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($exitCode -ne 0) { throw "k6 failed for $name" }

    $summary = Get-Content -Raw -Encoding utf8 $summaryPath | ConvertFrom-Json
    $accepted = Metric-Value $summary "partition_accepted" "count"
    $shed = Metric-Value $summary "partition_shed" "count"
    $unexpected = Metric-Value $summary "partition_unexpected" "count"
    $merchantOne = Metric-Value $summary "partition_merchant_1" "count"
    $merchantTwo = Metric-Value $summary "partition_merchant_2" "count"
    $total = $accepted + $shed + $unexpected

    Invoke-RestMethod -Method Post -Uri "$base/internal/v1/test-support/relay-outbox" -TimeoutSec 90 | Out-Null
    $converged = Wait-Convergence
    $afterOffsets = Get-KafkaOffsets
    $hotspot = Invoke-RestMethod -Uri "$base/internal/v1/test-support/hotspot" -TimeoutSec 15
    $overload = Invoke-RestMethod -Uri "$base/internal/v1/test-support/overload" -TimeoutSec 15
    $window = if ($Burst) { 25 } else { $measuredSeconds + 15 }
    $hikariPending = Query-Prometheus "max(max_over_time(hikaricp_connections_pending{job='payments-capacity'}[$($window)s]))"
    $hikariActive = Query-Prometheus "max(max_over_time(hikaricp_connections_active{job='payments-capacity'}[$($window)s]))"
    $processCpu = Query-Prometheus "max(max_over_time(process_cpu_usage{job='payments-capacity'}[$($window)s]))"
    $lockWaitAverage = if ($hotspot.lockWaitCount -eq 0) { 0 } else { $hotspot.lockWaitTotalMillis / $hotspot.lockWaitCount }
    $shedRate = if ($total -eq 0) { 0 } else { $shed / $total }

    return [pscustomobject][ordered]@{
        profile = $Profile
        offeredRate = $Rate
        burstRate = if ($Burst) { 400 } else { $null }
        repetition = $Repeat
        startedAt = $startedAt.ToString("o")
        accepted = $accepted
        shed = $shed
        shedRate = [math]::Round($shedRate, 6)
        unexpected = $unexpected
        p95Millis = Metric-Value $summary "partition_approval_duration" "p(95)"
        p99Millis = Metric-Value $summary "partition_approval_duration" "p(99)"
        merchantOneRatio = if (($merchantOne + $merchantTwo) -eq 0) { 0 } else { [math]::Round($merchantOne / ($merchantOne + $merchantTwo), 6) }
        kafkaDistribution = Get-KafkaDelta $beforeOffsets $afterOffsets
        hotspot = $hotspot
        overload = $overload
        resources = [ordered]@{
            hikariActive = $hikariActive
            hikariPending = $hikariPending
            lockWaitAverageMillis = [math]::Round($lockWaitAverage, 6)
            processCpu = $processCpu
        }
        consistency = $converged.consistency
        finalKafkaLag = $converged.kafkaLag
        dbPressure = $hikariPending -gt 0 -or $lockWaitAverage -ge 50
        providerPressure = $shedRate -ge 0.01 -or $overload.admissionRejected -gt 0
        summaryFile = $summaryPath
    }
}

Push-Location $repo
try {
    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    node scripts/analyze-step21-partitions.mjs docs/history/evidence/step21-partition-simulation.json
    if ($LASTEXITCODE -ne 0) { throw "Partition simulation failed." }
    docker compose --profile capacity up -d --build capacity-mysql capacity-redis zookeeper kafka mock-pg-capacity app-capacity payment-consumer-capacity prometheus
    if ($LASTEXITCODE -ne 0) { throw "Failed to start Step 21 services." }
    docker compose restart prometheus
    Wait-Http "$base/actuator/health"
    Wait-Http "$prometheus/-/ready"

    $runs = @()
    foreach ($profile in $profiles) {
        foreach ($rate in $rates) {
            for ($repeat = 1; $repeat -le $Repetitions; $repeat++) {
                Write-Host "Step 21: profile=$profile rate=$rate repeat=$repeat"
                $runs += Invoke-Run -Profile $profile -Rate $rate -Repeat $repeat -Burst $false
            }
        }
    }
    if (-not $Quick) {
        for ($repeat = 1; $repeat -le $Repetitions; $repeat++) {
            Write-Host "Step 21: profile=burst rate=100 burst=400 repeat=$repeat"
            $runs += Invoke-Run -Profile "burst" -Rate 100 -Repeat $repeat -Burst $true
        }
    }

    $required = [math]::Ceiling($Repetitions / 2.0)
    $dbBottleneckProfiles = @()
    foreach ($profile in $profiles) {
        $atPressure = @($runs | Where-Object { $_.profile -eq $profile -and $_.offeredRate -eq 200 })
        if (@($atPressure | Where-Object { $_.dbPressure -and -not $_.providerPressure }).Count -ge $required) {
            $dbBottleneckProfiles += $profile
        }
    }
    $decision = if ($dbBottleneckProfiles.Count -gt 0) { "PROCEED" } else { "DEFER" }
    $pressureSummary = @()
    foreach ($group in ($runs | Group-Object profile, offeredRate)) {
        $items = @($group.Group)
        $pressureSummary += [pscustomobject][ordered]@{
            profile = $items[0].profile
            offeredRate = $items[0].offeredRate
            burstRate = $items[0].burstRate
            repetitions = $items.Count
            averageP99Millis = [math]::Round(($items.p99Millis | Measure-Object -Average).Average, 6)
            maximumShedRate = ($items.shedRate | Measure-Object -Maximum).Maximum
            dbPressureRuns = @($items | Where-Object { $_.dbPressure }).Count
            providerPressureRuns = @($items | Where-Object { $_.providerPressure }).Count
            maximumHikariPending = ($items.resources.hikariPending | Measure-Object -Maximum).Maximum
            maximumLockWaitAverageMillis = ($items.resources.lockWaitAverageMillis | Measure-Object -Maximum).Maximum
            averageKafkaMaxToMeanRatio = [math]::Round(($items.kafkaDistribution.maxToMeanRatio | Measure-Object -Average).Average, 6)
            averageMerchantOneRatio = [math]::Round(($items.merchantOneRatio | Measure-Object -Average).Average, 6)
        }
    }
    $simulation = Get-Content -Raw -Encoding utf8 docs/history/evidence/step21-partition-simulation.json | ConvertFrom-Json
    $report = [ordered]@{
        step = 21
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        gitSha = (git rev-parse HEAD).Trim()
        fixedConditions = [ordered]@{
            profiles = @($profiles) + $(if ($Quick) { @() } else { @("burst") })
            rates = $rates
            burstRate = 400
            repetitions = $Repetitions
            warmupDuration = $WarmupDuration
            measuredDuration = $MeasuredDuration
            partitions = 6
            dbPressureRule = "Hikari pending > 0 or average DB lock wait >= 50ms"
            providerPressureRule = "shed rate >= 1% or provider admission rejected > 0"
            proceedRule = "At least half of 200 req/s repeats show DB pressure before provider pressure."
        }
        partitionSimulation = $simulation
        runs = $runs
        pressureSummary = $pressureSummary
        dbBottleneckProfiles = $dbBottleneckProfiles
        decision = $decision
        rationale = if ($decision -eq "PROCEED") {
            "A repeatable DB-first bottleneck was measured; a separately approved shard prototype is justified."
        } else {
            "Provider protection reached pressure before a repeatable DB-first bottleneck; shard complexity is not justified."
        }
        result = "PASS"
        limitation = "Local routing simulation and Docker pressure experiment; no production shard or routing layer was implemented."
    }
    [IO.File]::WriteAllText((Resolve-Path (Split-Path $ReportPath -Parent) | Join-Path -ChildPath (Split-Path $ReportPath -Leaf)), ($report | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
    Write-Host "Step 21 Acceptance Result: PASS, decision=$decision"
    Write-Host "Evidence: $ReportPath"
} finally {
    Pop-Location
}