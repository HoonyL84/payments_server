import http, { expectedStatuses } from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://host.docker.internal:8085';
const mockPg = __ENV.MOCK_PG_URL || 'http://host.docker.internal:8093';
const profile = __ENV.PRESSURE_PROFILE || 'uniform';
const rate = Number(__ENV.RATE || 100);
const burstRate = Number(__ENV.BURST_RATE || 400);
const warmupDuration = __ENV.WARMUP_DURATION || '3s';
const measuredDuration = __ENV.MEASURED_DURATION || '10s';
const maxVUs = Number(__ENV.MAX_VUS || Math.max(200, burstRate * 2));
const expected = expectedStatuses(200, 204, 429, 503);
const approvalDuration = new Trend('partition_approval_duration', true);
const accepted = new Counter('partition_accepted');
const shed = new Counter('partition_shed');
const unexpected = new Counter('partition_unexpected');
const merchantOne = new Counter('partition_merchant_1');
const merchantTwo = new Counter('partition_merchant_2');

const measuredScenario = profile === 'burst'
  ? {
      executor: 'ramping-arrival-rate',
      exec: 'measured',
      startTime: warmupDuration,
      startRate: rate,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(maxVUs, Math.max(100, rate)),
      maxVUs,
      stages: [
        { target: rate, duration: '3s' },
        { target: burstRate, duration: '1s' },
        { target: burstRate, duration: '3s' },
        { target: rate, duration: '1s' },
        { target: rate, duration: '2s' },
      ],
    }
  : {
      executor: 'constant-arrival-rate',
      exec: 'measured',
      startTime: warmupDuration,
      rate,
      timeUnit: '1s',
      duration: measuredDuration,
      preAllocatedVUs: Math.min(maxVUs, Math.max(50, rate)),
      maxVUs,
    };

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'warmup',
      rate: Math.min(rate, 100),
      timeUnit: '1s',
      duration: warmupDuration,
      preAllocatedVUs: Math.min(maxVUs, Math.max(50, rate)),
      maxVUs,
    },
    measured: measuredScenario,
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  mustStatus(http.post(`${mockPg}/internal/v1/test-support/reset`, null, params('30s')), 204, 'mock PG reset');
  mustStatus(http.put(
    `${mockPg}/internal/v1/test-support/behavior`,
    JSON.stringify({ approve: 'APPROVED', cancel: 'CANCELED', responseDelayMillis: 0, webhookEnabled: false }),
    params()
  ), 204, 'mock PG behavior');
  mustStatus(http.post(`${base}/internal/v1/test-support/reset`, null, params('30s')), 204, 'payment reset');
  return { runId: Date.now() };
}

export function warmup(data) {
  approve(data, false);
}

export function measured(data) {
  const response = approve(data, true);
  check(response, {
    'partition pressure response is accepted or deliberately shed': value =>
      value.status === 200 || value.status === 429 || value.status === 503,
  });
}

function approve(data, record) {
  const sequence = __VU + __ITER;
  const merchantId = selectMerchant(sequence);
  const id = `${data.runId}-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${base}/api/v1/payments/approve`,
    JSON.stringify({
      userId: `partition-user-${id}`,
      merchantId,
      orderId: `partition-order-${id}`,
      amountMinorUnits: 1000,
      currency: 'KRW',
    }),
    {
      ...params('10s'),
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': `partition-key-${id}`,
      },
    }
  );
  if (!record) return response;

  approvalDuration.add(response.timings.duration);
  if (merchantId === 'merchant-1') merchantOne.add(1);
  else merchantTwo.add(1);
  if (response.status === 200) accepted.add(1);
  else if (response.status === 429 || response.status === 503) shed.add(1);
  else unexpected.add(1);
  return response;
}

function selectMerchant(sequence) {
  if (profile === 'single-hot') return 'merchant-1';
  if (profile === 'merchant-80-20') return sequence % 10 < 8 ? 'merchant-1' : 'merchant-2';
  return sequence % 2 === 0 ? 'merchant-1' : 'merchant-2';
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