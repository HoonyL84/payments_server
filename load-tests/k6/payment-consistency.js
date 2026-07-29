import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const json = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  scenarios: {
    baseline: { executor: 'shared-iterations', exec: 'baseline', vus: 5, iterations: 50 },
    duplicate: { executor: 'shared-iterations', exec: 'duplicate', startTime: '8s', vus: 10, iterations: 50 },
    timeout: { executor: 'per-vu-iterations', exec: 'timeout', startTime: '16s', vus: 1, iterations: 1 },
    cancellation: { executor: 'shared-iterations', exec: 'cancellation', startTime: '20s', vus: 3, iterations: 15 },
    mixed: { executor: 'shared-iterations', exec: 'baseline', startTime: '28s', vus: 10, iterations: 100 }
  },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<1000'] }
};

export function setup() {
  const reset = http.post(`${base}/internal/v1/test-support/reset`, null, { timeout: '30s' });
  if (reset.status !== 204) throw new Error(`reset failed: ${reset.status}`);
}

function approve(key, order) {
  return http.post(`${base}/api/v1/payments/approve`, JSON.stringify({
    userId: `user-${order}`, merchantId: 'merchant-1', orderId: order,
    amountMinorUnits: 1000, currency: 'KRW'
  }), { headers: { ...json.headers, 'Idempotency-Key': key } });
}

export function baseline() {
  const id = `${__VU}-${__ITER}-${Date.now()}`;
  check(approve(`base-${id}`, `order-${id}`), { approved: r => r.status === 200 });
}

export function duplicate() {
  const id = `dup-${__VU}-${__ITER}`;
  const first = approve(id, id);
  const second = approve(id, id);
  check(first, { 'first approved': r => r.status === 200 });
  check(second, { 'duplicate reused': r => r.status === 200 && r.headers['Idempotency-Replayed'] === 'true' });
}

export function timeout() {
  http.post(`${base}/internal/v1/test-support/pg?approve=TIMED_OUT`);
  const response = approve('timeout-1', 'timeout-order-1');
  check(response, { pending: r => r.status === 200 && r.json('state') === 'PENDING_CONFIRMATION' });
  http.post(`${base}/internal/v1/test-support/pg?approve=APPROVED`);
  check(http.post(`${base}/internal/v1/payments/${response.json('paymentId')}/confirm`), { confirmed: r => r.status === 200 });
}

export function cancellation() {
  const id = `cancel-${__VU}-${__ITER}`;
  const approved = approve(id, id);
  const paymentId = approved.json('paymentId');
  const body = JSON.stringify({ amountMinorUnits: 500, currency: 'KRW' });
  const first = http.post(`${base}/api/v1/payments/${paymentId}/cancel`, body, { headers: { ...json.headers, 'Idempotency-Key': id } });
  const second = http.post(`${base}/api/v1/payments/${paymentId}/cancel`, body, { headers: { ...json.headers, 'Idempotency-Key': id } });
  check(first, { canceled: r => r.status === 200 });
  check(second, { 'cancellation duplicate reused': r => r.status === 200 && r.headers['Idempotency-Replayed'] === 'true' });
}

export function teardown() {
  const relay = http.post(`${base}/internal/v1/test-support/relay-outbox`, null, { timeout: '60s' });
  if (relay.status !== 200) throw new Error(`outbox relay failed: ${relay.status}`);
  const response = http.get(`${base}/internal/v1/test-support/consistency`);
  if (response.status !== 200) throw new Error(`consistency report failed: ${response.status}`);
  const report = response.json();
  if (report.duplicatePayments !== 0 || report.duplicateCancellations !== 0 || report.ledgerDrift !== 0 ||
      report.processingIdempotency !== 0 || report.pendingConfirmations !== 0 ||
      report.pendingOutbox !== 0 || report.invalidTransitions !== 0) {
    throw new Error(`consistency failure: ${JSON.stringify(report)}`);
  }
}
