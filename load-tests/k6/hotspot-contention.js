import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const expectedGate = (__ENV.EXPECT_REDIS_GATE || 'true') === 'true';
const stormSize = Number(__ENV.STORM_SIZE || 100);
const json = { headers: { 'Content-Type': 'application/json' } };
const successOrConflict = http.expectedStatuses(200, 409);
const conflict = http.expectedStatuses(409);

export const options = {
  scenarios: {
    hotspot: { executor: 'shared-iterations', vus: 1, iterations: 1 }
  },
  batchPerHost: stormSize,
  thresholds: {
    checks: ['rate==1'],
    http_req_duration: ['p(95)<2000']
  }
};

export function setup() {
  const reset = http.post(`${base}/internal/v1/test-support/reset`, null, { timeout: '30s' });
  if (reset.status !== 204) throw new Error(`reset failed: ${reset.status}`);
  const delay = http.post(`${base}/internal/v1/test-support/pg-delay?millis=300`);
  if (delay.status !== 204) throw new Error(`PG delay setup failed: ${delay.status}`);
}

function approvalBody(amount = 1000) {
  return JSON.stringify({
    userId: 'hot-user',
    merchantId: 'merchant-1',
    orderId: 'hot-order',
    amountMinorUnits: amount,
    currency: 'KRW'
  });
}

function storm(url, body, key, size) {
  const requests = Array.from({ length: size }, () => [
    'POST',
    url,
    body,
    {
      headers: { ...json.headers, 'Idempotency-Key': key },
      timeout: '10s',
      responseCallback: successOrConflict
    }
  ]);
  return http.batch(requests);
}

export default function () {
  const approvalKey = 'hot-approval-key';
  const approvalResponses = storm(
    `${base}/api/v1/payments/approve`,
    approvalBody(),
    approvalKey,
    stormSize
  );
  const approved = approvalResponses.filter(response => response.status === 200);
  check(approvalResponses, {
    'approval storm has one winner': () => approved.length === 1,
    'approval duplicates are controlled': responses => responses.every(response => [200, 409].includes(response.status))
  });

  const paymentId = approved[0].json('paymentId');
  const approvalReplay = http.post(
    `${base}/api/v1/payments/approve`,
    approvalBody(),
    { headers: { ...json.headers, 'Idempotency-Key': approvalKey } }
  );
  check(approvalReplay, {
    'approval replay reuses result': response => response.status === 200 && response.headers['Idempotency-Replayed'] === 'true'
  });

  const collision = http.post(
    `${base}/api/v1/payments/approve`,
    approvalBody(2000),
    {
      headers: { ...json.headers, 'Idempotency-Key': approvalKey },
      responseCallback: conflict
    }
  );
  check(collision, { 'payload collision is rejected': response => response.status === 409 });

  const cancellationKey = 'hot-cancellation-key';
  const cancellationBody = JSON.stringify({ amountMinorUnits: 500, currency: 'KRW' });
  const cancellationResponses = storm(
    `${base}/api/v1/payments/${paymentId}/cancel`,
    cancellationBody,
    cancellationKey,
    Math.max(20, Math.floor(stormSize / 2))
  );
  check(cancellationResponses, {
    'cancellation storm has one winner': responses => responses.filter(response => response.status === 200).length === 1,
    'cancellation duplicates are controlled': responses => responses.every(response => [200, 409].includes(response.status))
  });

  const cancellationReplay = http.post(
    `${base}/api/v1/payments/${paymentId}/cancel`,
    cancellationBody,
    { headers: { ...json.headers, 'Idempotency-Key': cancellationKey } }
  );
  check(cancellationReplay, {
    'cancellation replay reuses result': response => response.status === 200 && response.headers['Idempotency-Replayed'] === 'true'
  });

  const relay = http.post(`${base}/internal/v1/test-support/relay-outbox`, null, { timeout: '30s' });
  check(relay, { 'outbox relay succeeds': response => response.status === 200 });

  const consistency = http.get(`${base}/internal/v1/test-support/consistency`).json();
  check(consistency, {
    'hotspot keeps consistency': report => Object.values(report).every(value => value === 0)
  });

  const report = http.get(`${base}/internal/v1/test-support/hotspot`).json();
  check(report, {
    'approval PG side effect is one': value => value.approveCalls === 1,
    'cancellation PG side effect is one': value => value.cancelCalls === 1,
    'provider request ids stay unique': value => value.approvalAttempts === 1 && value.cancellationAttempts === 1,
    'gate mode matches run': value => expectedGate ? value.gateRejected > 0 : value.gateBypassed >= stormSize,
    'Redis gate remains available': value => !expectedGate || value.gateUnavailable === 0,
    'contention profile matches mode': value => expectedGate
      ? value.dbTransactionCount <= 10 && value.lockWaitCount <= 5
      : value.dbTransactionCount >= stormSize && value.lockWaitCount >= 20
  });
  console.log(`HOTSPOT_REPORT ${JSON.stringify(report)}`);
}

export function teardown() {
  http.post(`${base}/internal/v1/test-support/pg-delay?millis=0`);
}
