import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const base = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const headers = { 'Content-Type': 'application/json' };

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] }
};

function post(path, body, params = {}) {
  return http.post(`${base}${path}`, body, params);
}

function arm(point) {
  const response = post(`/internal/v1/test-support/fault?point=${point}`);
  if (response.status !== 204) fail(`failed to arm ${point}`);
}

function approve(key, order) {
  return post('/api/v1/payments/approve', JSON.stringify({
    userId: `user-${order}`,
    merchantId: 'merchant-1',
    orderId: order,
    amountMinorUnits: 1000,
    currency: 'KRW'
  }), { headers: { ...headers, 'Idempotency-Key': key } });
}

export function setup() {
  const reset = post('/internal/v1/test-support/reset');
  if (reset.status !== 204) fail(`reset failed: ${reset.status}`);
}

export default function () {
  arm('BEFORE_PG');
  check(approve('before-pg', 'before-pg'), { 'before PG interruption': r => r.status === 500 });

  arm('AFTER_PG_BEFORE_DB');
  check(approve('after-pg', 'after-pg'), { 'after PG interruption': r => r.status === 500 });

  post('/internal/v1/test-support/pg?approve=TIMED_OUT');
  const pending = approve('confirm-stop', 'confirm-stop');
  post('/internal/v1/test-support/pg?approve=APPROVED');
  arm('CONFIRMING_WORKER_STOP');
  check(post(`/internal/v1/payments/${pending.json('paymentId')}/confirm`), {
    'confirm worker interruption': r => r.status === 500
  });

  check(approve('outbox-pending', 'outbox-pending'), { 'approval before relay': r => r.status === 200 });
  arm('AFTER_DB_BEFORE_OUTBOX_RELAY');
  check(post('/internal/v1/test-support/relay-outbox'), { 'before relay interruption': r => r.status === 500 });

  arm('AFTER_OUTBOX_PUBLISH_BEFORE_STATUS_UPDATE');
  check(post('/internal/v1/test-support/relay-outbox'), { 'after publish interruption handled': r => r.status === 200 });

  sleep(2);
  const before = http.get(`${base}/internal/v1/recovery/report?staleSeconds=1`).json();
  check(before, {
    'manual review separated': report => report.manualReviewCount === 3,
    'outbox recovery detected': report => report.autoRecoverableCount > 0
  });

  const recovery = post('/internal/v1/recovery/run?staleSeconds=1');
  check(recovery, { 'recovery run succeeds': r => r.status === 200 && r.json('failures').length === 0 });

  const after = http.get(`${base}/internal/v1/recovery/report?staleSeconds=1`).json();
  const consistency = http.get(`${base}/internal/v1/test-support/consistency`).json();
  check(after, {
    'automatic candidates converge': report => report.autoRecoverableCount === 0,
    'ambiguous states remain manual': report => report.manualReviewCount === 3
  });
  check(consistency, {
    'ledger remains balanced': report => report.ledgerDrift === 0,
    'outbox converges': report => report.pendingOutbox === 0
  });
}
