import http, { expectedStatuses } from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://host.docker.internal:8085';
const mockPg = __ENV.MOCK_PG_URL || 'http://host.docker.internal:8093';
const scenario = __ENV.CAPACITY_SCENARIO || 'normal';
const rate = Number(__ENV.RATE || 10);
const warmupDuration = __ENV.WARMUP_DURATION || '5s';
const measuredDuration = __ENV.MEASURED_DURATION || '15s';
const maxVUs = Number(__ENV.MAX_VUS || Math.max(50, rate * 2));
const expected = expectedStatuses(200, 204, 429, 503);
const approvalDuration = new Trend('capacity_approval_duration', true);
const accepted = new Counter('capacity_accepted');
const shed = new Counter('capacity_shed');
const unexpected = new Counter('capacity_unexpected');
const statusZero = new Counter('capacity_status_0');
const statusConflict = new Counter('capacity_status_409');
const statusServerError = new Counter('capacity_status_5xx');
const statusOther = new Counter('capacity_status_other');
const merchantOne = new Counter('capacity_merchant_1');
const merchantTwo = new Counter('capacity_merchant_2');
const acceptedRate = new Rate('capacity_accepted_rate');

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'warmup',
      rate,
      timeUnit: '1s',
      duration: warmupDuration,
      preAllocatedVUs: Math.min(maxVUs, Math.max(10, rate)),
      maxVUs,
    },
    measured: {
      executor: 'constant-arrival-rate',
      exec: 'measured',
      startTime: warmupDuration,
      rate,
      timeUnit: '1s',
      duration: measuredDuration,
      preAllocatedVUs: Math.min(maxVUs, Math.max(10, rate)),
      maxVUs,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  mustStatus(http.post(`${mockPg}/internal/v1/test-support/reset`, null, params('30s')), 204, 'mock PG reset');
  const delay = scenario === 'pg-delay' ? 150 : 0;
  const behavior = http.put(
    `${mockPg}/internal/v1/test-support/behavior`,
    JSON.stringify({
      approve: 'APPROVED',
      cancel: 'CANCELED',
      responseDelayMillis: delay,
      webhookEnabled: false,
    }),
    params()
  );
  mustStatus(behavior, 204, 'mock PG behavior');
  mustStatus(http.post(`${base}/internal/v1/test-support/reset`, null, params('30s')), 204, 'payment reset');
  return { runId: Date.now() };
}

export function warmup(data) {
  requestApproval(data, false);
}

export function measured(data) {
  const response = requestApproval(data, true);
  check(response, {
    'capacity response is accepted or deliberately shed': value =>
      value.status === 200 || value.status === 429 || value.status === 503,
  });
}

export function teardown() {
  http.post(`${base}/internal/v1/test-support/relay-outbox-once?limit=100`, null, params('30s'));
}

function requestApproval(data, record) {
  const id = `${data.runId}-${__VU}-${__ITER}-${Date.now()}`;
  const merchantId = scenario === 'merchant-skew' && (__ITER % 10 === 9)
    ? 'merchant-2'
    : 'merchant-1';
  const response = http.post(
    `${base}/api/v1/payments/approve`,
    JSON.stringify({
      userId: `capacity-user-${id}`,
      merchantId,
      orderId: `capacity-order-${id}`,
      amountMinorUnits: 1000,
      currency: 'KRW',
    }),
    {
      ...params('10s'),
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': `capacity-key-${id}`,
      },
    }
  );
  if (!record) return response;

  approvalDuration.add(response.timings.duration);
  if (merchantId === 'merchant-1') merchantOne.add(1);
  else merchantTwo.add(1);
  if (response.status === 200) {
    accepted.add(1);
    acceptedRate.add(true);
  } else if (response.status === 429 || response.status === 503) {
    shed.add(1);
    acceptedRate.add(false);
  } else {
    unexpected.add(1);
    if (response.status === 0) statusZero.add(1);
    else if (response.status === 409) statusConflict.add(1);
    else if (response.status >= 500) statusServerError.add(1);
    else statusOther.add(1);
    acceptedRate.add(false);
  }
  return response;
}

function params(timeout = '10s') {
  return {
    headers: { 'Content-Type': 'application/json' },
    responseCallback: expected,
    timeout,
  };
}

function mustStatus(response, status, action) {
  if (response.status !== status) fail(`${action} failed: ${response.status}`);
}
