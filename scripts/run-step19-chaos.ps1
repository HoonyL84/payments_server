param(
    [string]$EvidencePath = "docs/history/evidence/step19-chaos.json"
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$evidenceDirectory = Split-Path -Parent (Join-Path $repo $EvidencePath)
New-Item -ItemType Directory -Force $evidenceDirectory | Out-Null
$base = "http://localhost:8084"
$toxi = "http://localhost:8474"
$kafkaPaused = $false

function Invoke-Json {
    param([string]$Method, [string]$Uri, $Body = $null)
    $arguments = @{
        Method = $Method
        Uri = $Uri
        ContentType = "application/json"
        TimeoutSec = 30
        UserAgent = "toxiproxy-cli"
    }
    if ($null -ne $Body) {
        $arguments.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    Invoke-RestMethod @arguments
}

function Wait-Http {
    param([string]$Uri, [int]$Seconds = 90)
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try {
            Invoke-RestMethod -Uri $Uri -UserAgent "toxiproxy-cli" -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 1
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri"
}

function Set-Proxy {
    param([string]$Name, [string]$Listen, [string]$Upstream)
    try {
        Invoke-RestMethod -Method Delete -Uri "$toxi/proxies/$Name" -UserAgent "toxiproxy-cli" -TimeoutSec 5 | Out-Null
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 404) {
            throw
        }
    }
    Invoke-Json -Method Post -Uri "$toxi/proxies" -Body @{
        name = $Name
        listen = $Listen
        upstream = $Upstream
        enabled = $true
    } | Out-Null
}

function Approve {
    param([string]$Key)
    $arguments = @{
        Method = "Post"
        Uri = "$base/api/v1/payments/approve"
        ContentType = "application/json"
        Headers = @{ "Idempotency-Key" = $Key }
        Body = @{
            userId = "user-$Key"
            merchantId = "merchant-1"
            orderId = "order-$Key"
            amountMinorUnits = 1000
            currency = "KRW"
        } | ConvertTo-Json -Compress
        TimeoutSec = 10
    }
    Invoke-RestMethod @arguments
}

function Get-Consistency {
    Invoke-RestMethod -Uri "$base/internal/v1/test-support/consistency" -TimeoutSec 10
}

function Wait-EffectCount {
    param([long]$Expected, [int]$Seconds = 60)
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $report = Get-Consistency
        if ($report.paymentEventEffects -ge $Expected) {
            return $report
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Consumer did not reach paymentEventEffects=$Expected"
}

Push-Location $repo
try {
        docker compose --profile chaos up -d --build chaos-mysql chaos-redis chaos-zookeeper chaos-kafka mock-pg-chaos toxiproxy

    if ($LASTEXITCODE -ne 0) { throw "Failed to start Step 19 dependencies." }
    Wait-Http "$toxi/version"
    Set-Proxy -Name "pg" -Listen "0.0.0.0:8666" -Upstream "mock-pg-chaos:8090"
    Set-Proxy -Name "redis" -Listen "0.0.0.0:8668" -Upstream "chaos-redis:6379"
    Set-Proxy -Name "mysql" -Listen "0.0.0.0:8669" -Upstream "chaos-mysql:3306"

    docker compose --profile chaos up -d --build app-chaos payment-consumer-chaos prometheus
    if ($LASTEXITCODE -ne 0) { throw "Failed to start Step 19 application services." }
    Wait-Http "$base/actuator/health"

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker pull grafana/k6:0.54.0 2>&1 | Write-Host
    $pullExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($pullExitCode -ne 0) { throw "Failed to pull the pinned k6 image." }

    $startedAt = (Get-Date).ToUniversalTime()
    $ErrorActionPreference = "Continue"
    & docker run --rm -e BASE_URL=http://host.docker.internal:8084 -e MOCK_PG_URL=http://host.docker.internal:8094 -e TOXIPROXY_URL=http://host.docker.internal:8474 -v "$($repo):/work" grafana/k6:0.54.0 run --summary-export /work/docs/history/evidence/step19-k6.json /work/load-tests/k6/failure-propagation-chaos.js 2>&1 | Tee-Object -Variable capturedK6
    $k6ExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($k6ExitCode -ne 0) { throw "Step 19 k6 network fault phase failed." }


$k6Summary = Get-Content -Raw -Encoding utf8 "docs/history/evidence/step19-k6.json" | ConvertFrom-Json

    $beforeOutbox = Get-Consistency
    Approve -Key "chaos-outbox-worker-stop" | Out-Null
    Invoke-RestMethod -Method Post -Uri "$base/internal/v1/test-support/fault?point=AFTER_OUTBOX_PUBLISH_BEFORE_STATUS_UPDATE" -TimeoutSec 10 | Out-Null
    $outboxInterruptedAt = (Get-Date).ToUniversalTime()
    $outboxInterruptionStatus = 0
    try {
        Invoke-RestMethod -Method Post -Uri "$base/internal/v1/test-support/relay-outbox-once?limit=100" -TimeoutSec 30 | Out-Null
        $outboxInterruptionStatus = 200
    } catch {
        $outboxInterruptionStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($outboxInterruptionStatus -ne 500) { throw "Expected interrupted outbox relay to return 500, got $outboxInterruptionStatus." }
    Start-Sleep -Seconds 31
    $outboxRetry = Invoke-RestMethod -Method Post -Uri "$base/internal/v1/test-support/relay-outbox-once?limit=100" -TimeoutSec 30
    if ($outboxRetry.published -lt 1) { throw "Interrupted outbox event was not reclaimed." }
    $afterOutbox = Wait-EffectCount -Expected ($beforeOutbox.paymentEventEffects + 1)
    if ($afterOutbox.paymentEventEffects -ne ($beforeOutbox.paymentEventEffects + 1) -or
        $afterOutbox.processedEvents -ne $afterOutbox.paymentEventEffects -or
        $afterOutbox.pendingOutbox -ne 0) {
        throw "Outbox retry duplicated the consumer side effect or failed to converge."
    }
    $outboxRecoveryMillis = [math]::Round(((Get-Date).ToUniversalTime() - $outboxInterruptedAt).TotalMilliseconds, 3)

    $beforeKafka = Get-Consistency
    $kafkaOutageStarted = (Get-Date).ToUniversalTime()
    docker compose --profile chaos pause chaos-kafka
    if ($LASTEXITCODE -ne 0) { throw "Failed to pause Kafka." }
    $kafkaPaused = $true
    try {
        Approve -Key "chaos-kafka-outage" | Out-Null
        $relayWhileDown = Invoke-RestMethod -Method Post -Uri "$base/internal/v1/test-support/relay-outbox-once?limit=100" -TimeoutSec 15
        if ($relayWhileDown.published -ne 0) { throw "Outbox was marked published while Kafka was unavailable." }
    } finally {
        docker compose --profile chaos unpause chaos-kafka
        $kafkaPaused = $false
    }
    Start-Sleep -Seconds 3
    $relayAfterKafka = Invoke-RestMethod -Method Post -Uri "$base/internal/v1/test-support/relay-outbox" -TimeoutSec 30
    $afterKafka = Wait-EffectCount -Expected ($beforeKafka.paymentEventEffects + 1)
    $kafkaRecoveryMillis = [math]::Round(((Get-Date).ToUniversalTime() - $kafkaOutageStarted).TotalMilliseconds, 3)

    $consumerStoppedAt = (Get-Date).ToUniversalTime()
    docker compose --profile chaos stop payment-consumer-chaos
    $beforeWorkerStop = Get-Consistency
    Approve -Key "chaos-consumer-stop" | Out-Null
    Invoke-RestMethod -Method Post -Uri "$base/internal/v1/test-support/relay-outbox" -TimeoutSec 30 | Out-Null
    Start-Sleep -Seconds 2
    $duringWorkerStop = Get-Consistency
    if ($duringWorkerStop.paymentEventEffects -ne $beforeWorkerStop.paymentEventEffects) { throw "Consumer side effect changed while the worker was stopped." }
    docker compose --profile chaos start payment-consumer-chaos
    $afterWorkerRestart = Wait-EffectCount -Expected ($beforeWorkerStop.paymentEventEffects + 1)
    $consumerRecoveryMillis = [math]::Round(((Get-Date).ToUniversalTime() - $consumerStoppedAt).TotalMilliseconds, 3)

    $appRestartedAt = (Get-Date).ToUniversalTime()
    docker compose --profile chaos restart app-chaos
    Wait-Http "$base/actuator/health"
    Approve -Key "chaos-app-restart" | Out-Null
    $appRecoveryMillis = [math]::Round(((Get-Date).ToUniversalTime() - $appRestartedAt).TotalMilliseconds, 3)
    Invoke-RestMethod -Method Post -Uri "$base/internal/v1/test-support/relay-outbox" -TimeoutSec 30 | Out-Null
    $final = Wait-EffectCount -Expected ($afterWorkerRestart.paymentEventEffects + 1)
    if ($final.ledgerDrift -ne 0 -or $final.processingIdempotency -ne 0 -or $final.pendingConfirmations -ne 0 -or $final.pendingOutbox -ne 0 -or $final.processedEvents -ne $final.paymentEventEffects) {
        throw "Step 19 final convergence failed: $($final | ConvertTo-Json -Compress)"
    }

    $evidence = [ordered]@{
        step = 19
        startedAt = $startedAt.ToString("o")
        completedAt = (Get-Date).ToUniversalTime().ToString("o")
        pgRedisMysqlFaultChecks = "PASS"
        networkFaults = [ordered]@{
            checksPassed = $k6Summary.metrics.checks.passes
            checksFailed = $k6Summary.metrics.checks.fails
            dependencyFailureP99Millis = $k6Summary.metrics.dependency_failure_duration.'p(99)'
        }
        outboxWorkerInterruption = [ordered]@{
            interruptedStatus = $outboxInterruptionStatus
            retryPublished = $outboxRetry.published
            effectsAfterRecovery = $afterOutbox.paymentEventEffects
            recoveryMillis = $outboxRecoveryMillis
        }
        kafkaOutage = [ordered]@{
            relayWhileDown = $relayWhileDown.published
            relayAfterRecovery = $relayAfterKafka.published
            effectsAfterRecovery = $afterKafka.paymentEventEffects
            recoveryMillis = $kafkaRecoveryMillis
        }
        consumerRestart = [ordered]@{
            effectsWhileStopped = $duringWorkerStop.paymentEventEffects
            effectsAfterRestart = $afterWorkerRestart.paymentEventEffects
            recoveryMillis = $consumerRecoveryMillis
        }
        appRestart = [ordered]@{ result = "PASS"; recoveryMillis = $appRecoveryMillis }
        finalConsistency = $final
        result = "PASS"
    }
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 $EvidencePath
    Write-Host "Step 19 Acceptance Result: PASS"
    Write-Host "Evidence: $EvidencePath"
} finally {
    if ($kafkaPaused) {
        docker compose --profile chaos unpause chaos-kafka 2>$null
    }

    Pop-Location
}
