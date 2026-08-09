import http, { expectedStatuses } from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL || 'http://localhost:8081';
const expected = expectedStatuses(200, 204, 429, 503);
const jsonParams = {
  headers: { 'Content-Type': 'application/json' },
  responseCallback: expected,
  timeout: '10s',
};

export const options = {
  batch: 50,
  batchPerHost: 50,
  scenarios: {
    overload: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
};

export default function () {
  reset();
  setProvider('APPROVED', 'APPROVED', 800);

  const burst = http.batch(Array.from({ length: 40 }, (_, index) => approvalRequest(index)));
  const accepted = burst.filter(response => response.status === 200);
  const shed = burst.filter(response => response.status === 429 || response.status === 503);
  const paymentIds = accepted.map(response => response.json('paymentId'));

  check({ accepted, shed }, {
    'command admission accepts no more than ten': value => value.accepted.length > 0 && value.accepted.length <= 10,
    'remaining command burst is shed': value => value.accepted.length + value.shed.length === 40,
  });

  const afterOpen = http.batch(Array.from({ length: 5 }, (_, index) => approvalRequest(100 + index)));
  const commandReport = overloadReport();
  check({ afterOpen, commandReport, accepted }, {
    'open command circuit rejects before another payment is created': value =>
      value.afterOpen.every(response => response.status === 503) &&
      value.commandReport.payments === value.accepted.length,
    'accepted commands become bounded unknown payments': value =>
      value.commandReport.approveCalls === value.accepted.length &&
      value.commandReport.providerTimeouts === value.accepted.length &&
      value.commandReport.processingIdempotency === 0,
    'command circuit is open after repeated timeouts': value => value.commandReport.commandCircuit === 'OPEN',
  });

  setProvider('APPROVED', 'UNKNOWN', 400);
  const confirmations = http.batch(paymentIds.map(paymentId => ({
    method: 'POST',
    url: `${base}/internal/v1/payments/${paymentId}/confirm`,
    params: { responseCallback: expected, timeout: '10s' },
  })));
  const inquiryReport = overloadReport();
  check({ confirmations, inquiryReport, accepted }, {
    'confirmation retry amplification stays within request budget': value =>
      value.inquiryReport.confirmApproveCalls <= value.accepted.length * 3,
    'global concurrent retry limit is respected': value => value.inquiryReport.maxRetryInFlight <= 2,
    'inquiry queue never exceeds configured capacity': value => value.inquiryReport.maxInquiryQueueDepth <= 2,
    'executed and shed confirmations both remain safely pending': value =>
      value.confirmations.every(response => response.status === 200 || response.status === 429) &&
      value.inquiryReport.pendingPayments === value.accepted.length,
    'retry pressure is suppressed instead of growing': value => value.inquiryReport.retrySuppressed > 0,
  });

  post('/internal/v1/test-support/provider-protection/reset');
  setProvider('APPROVED', 'APPROVED', 0);
  for (const paymentId of paymentIds) {
    post(`/internal/v1/payments/${paymentId}/confirm`);
  }
  post('/internal/v1/test-support/relay-outbox', null, { timeout: '30s' });

  const consistency = get('/internal/v1/test-support/consistency').json();
  const finalReport = overloadReport();
  check({ consistency, finalReport, accepted }, {
    'all accepted payments converge after provider recovery': value =>
      value.finalReport.approvedPayments === value.accepted.length &&
      value.finalReport.pendingPayments === 0,
    'idempotency and ledger remain consistent': value =>
      value.consistency.processingIdempotency === 0 && value.consistency.ledgerDrift === 0,
    'outbox backlog converges to zero': value => value.consistency.pendingOutbox === 0,
    'invalid transitions remain zero': value => value.consistency.invalidTransitions === 0,
  });

  console.log(`LOAD_SHEDDING_COMMAND ${JSON.stringify(commandReport)}`);
  console.log(`LOAD_SHEDDING_INQUIRY ${JSON.stringify(inquiryReport)}`);
  console.log(`LOAD_SHEDDING_FINAL ${JSON.stringify(finalReport)}`);
}

function approvalRequest(index) {
  const key = `overload-key-${index}`;
  return {
    method: 'POST',
    url: `${base}/api/v1/payments/approve`,
    body: JSON.stringify({
      userId: `overload-user-${index}`,
      merchantId: 'merchant-1',
      orderId: `overload-order-${index}`,
      amountMinorUnits: 10000 + index,
      currency: 'KRW',
    }),
    params: {
      ...jsonParams,
      headers: {
        ...jsonParams.headers,
        'Idempotency-Key': key,
      },
    },
  };
}

function reset() {
  const response = post('/internal/v1/test-support/reset', null, { timeout: '30s' });
  if (response.status !== 204) throw new Error(`reset failed: ${response.status}`);
}

function setProvider(approve, confirmApprove, delayMillis) {
  const behavior = post(
    `/internal/v1/test-support/pg?approve=${approve}&confirmApprove=${confirmApprove}`
  );
  const delay = post(`/internal/v1/test-support/pg-delay?millis=${delayMillis}`);
  if (behavior.status !== 204 || delay.status !== 204) {
    throw new Error(`provider setup failed: behavior=${behavior.status}, delay=${delay.status}`);
  }
}

function overloadReport() {
  return get('/internal/v1/test-support/overload').json();
}

function get(path) {
  return http.get(`${base}${path}`, { responseCallback: expected, timeout: '10s' });
}

function post(path, body = null, overrides = {}) {
  return http.post(`${base}${path}`, body, {
    responseCallback: expected,
    timeout: '10s',
    ...overrides,
  });
}
