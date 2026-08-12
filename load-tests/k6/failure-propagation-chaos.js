import http, { expectedStatuses } from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://host.docker.internal:8084';
const mockPg = __ENV.MOCK_PG_URL || 'http://host.docker.internal:8090';
const toxiproxy = __ENV.TOXIPROXY_URL || 'http://host.docker.internal:8474';
const expected = expectedStatuses(200, 204, 409, 429, 500, 503);
const dependencyFailureDuration = new Trend('dependency_failure_duration', true);

export const options = {
  batch: 100,
  batchPerHost: 100,
  vus: 1,
  iterations: 1,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'],
    dependency_failure_duration: ['p(99)<6000'],
  },
};

export function setup() {
  ['pg', 'redis', 'mysql'].forEach(name => setProxy(name, true));
  deleteToxic('pg', 'pg-latency');
  mustStatus(http.post(`${mockPg}/internal/v1/test-support/reset`, null, params()), 204, 'mock PG reset');
  mustStatus(post('/internal/v1/test-support/reset', null, { timeout: '30s' }), 204, 'payment reset');
}

export default function () {
  const baseline = approve('chaos-baseline', 'chaos-baseline');
  check(baseline, {
    'baseline approval succeeds': response =>
      response.status === 200 && response.json('state') === 'APPROVED',
  });

  addToxic('pg', {
    name: 'pg-latency',
    type: 'latency',
    stream: 'downstream',
    toxicity: 1,
    attributes: { latency: 2500, jitter: 100 },
  });
  const delayed = approve('chaos-pg-delay', 'chaos-pg-delay');
  dependencyFailureDuration.add(delayed.timings.duration);
  check(delayed, {
    'PG response loss becomes pending instead of failed': response =>
      response.status === 200 && response.json('state') === 'PENDING_CONFIRMATION',
  });
  deleteToxic('pg', 'pg-latency');
  sleep(1);
  const confirmed = post(`/internal/v1/payments/${delayed.json('paymentId')}/confirm`);
  check(confirmed, {
    'PG recovery converges pending payment': response =>
      response.status === 200 && response.json('state') === 'APPROVED',
  });

  addToxic('pg', {
    name: 'pg-reset',
    type: 'reset_peer',
    stream: 'downstream',
    toxicity: 1,
    attributes: { timeout: 0 },
  });
  const resetPeer = approve('chaos-pg-reset', 'chaos-pg-reset');
  check(resetPeer, {
    'PG connection reset becomes pending': response =>
      response.status === 200 && response.json('state') === 'PENDING_CONFIRMATION',
  });
  deleteToxic('pg', 'pg-reset');
  sleep(1);
  const resetRecovered = post(`/internal/v1/payments/${resetPeer.json('paymentId')}/confirm`);
  check(resetRecovered, {
    'connection reset recovers by PG confirmation': response =>
      response.status === 200 && response.json('state') === 'APPROVED',
  });

  addToxic('pg', {
    name: 'pg-pressure-latency',
    type: 'latency',
    stream: 'downstream',
    toxicity: 1,
    attributes: { latency: 2500, jitter: 100 },
  });
  const pgPressure = approvalBatch('chaos-pg-pressure', 24);
  const pendingPressure = pgPressure.filter(response =>
    response.status === 200 && response.json('state') === 'PENDING_CONFIRMATION');
  const shedPressure = pgPressure.filter(response => response.status === 429 || response.status === 503);
  check({ responses: pgPressure, pending: pendingPressure, shed: shedPressure }, {
    'PG latency activates bounded admission': result =>
      result.shed.length > 0 && result.pending.length <= 10 &&
      result.responses.every(response => response.status === 200 || response.status === 429 || response.status === 503),
  });
  deleteToxic('pg', 'pg-pressure-latency');
  sleep(3);
  pendingPressure.forEach(response => {
    const recovery = post(`/internal/v1/payments/${response.json('paymentId')}/confirm`);
    check(recovery, {
      'admitted PG timeout converges after recovery': value =>
        value.status === 200 && value.json('state') === 'APPROVED',
    });
  });

  setProxy('pg', false);
  const interrupted = approve('chaos-pg-interrupted', 'chaos-pg-interrupted');
  check(interrupted, {
    'PG interruption becomes pending': response =>
      response.status === 200 && response.json('state') === 'PENDING_CONFIRMATION',
  });
  const retryWhileDown = post(`/internal/v1/payments/${interrupted.json('paymentId')}/confirm`);
  check(retryWhileDown, {
    'confirmation retry budget returns unknown without hanging': response =>
      response.status === 200 && response.json('state') === 'PENDING_CONFIRMATION',
  });
  setProxy('pg', true);
  mustStatus(post('/internal/v1/test-support/provider-protection/reset'), 204, 'provider protection reset');

  addToxic('redis', {
    name: 'redis-latency',
    type: 'latency',
    stream: 'downstream',
    toxicity: 1,
    attributes: { latency: 700, jitter: 50 },
  });
  const redisDelayed = approve('chaos-redis-delay', 'chaos-redis-delay');
  dependencyFailureDuration.add(redisDelayed.timings.duration);
  deleteToxic('redis', 'redis-latency');
  check(redisDelayed, {
    'Redis latency falls back to DB within the request budget': response =>
      response.status === 200 && response.timings.duration < 3000,
  });

  setProxy('redis', false);
  const redisDown = approve('chaos-redis-down', 'chaos-redis-down');
  dependencyFailureDuration.add(redisDown.timings.duration);
  setProxy('redis', true);
  check(redisDown, {
    'Redis outage falls back to DB idempotency': response =>
      response.status === 200 && response.json('state') === 'APPROVED',
  });

  addToxic('mysql', {
    name: 'mysql-latency',
    type: 'latency',
    stream: 'downstream',
    toxicity: 1,
    attributes: { latency: 1200, jitter: 100 },
  });
  const mysqlPressure = approvalBatch('chaos-mysql-pressure', 20);
  deleteToxic('mysql', 'mysql-latency');
  check(mysqlPressure, {
    'MySQL latency exhausts the pool without unbounded requests': responses =>
      responses.some(response => response.status !== 200) &&
      responses.every(response =>
        [0, 200, 409, 429, 500, 503].includes(response.status) && response.timings.duration < 8000),
  });
  waitForHealth();

  setProxy('mysql', false);
  const mysqlDown = approve('chaos-mysql-down', 'chaos-mysql-down');
  dependencyFailureDuration.add(mysqlDown.timings.duration);
  setProxy('mysql', true);
  check(mysqlDown, {
    'MySQL outage is bounded as an unavailable request': response =>
      response.status === 500 || response.status === 503 || response.status === 0,
  });
  waitForHealth();
  const afterMysql = waitForApproval('chaos-mysql-recovered');
  check(afterMysql, {
    'application accepts requests after MySQL recovery': response =>
      response.status === 200 && response.json('state') === 'APPROVED',
  });

  sleep(1);
  const overload = get('/internal/v1/test-support/overload').json();
  check(overload, {
    'admission rejects excess PG pressure': report => report.admissionRejected > 0,
    'provider timeout or circuit protection is observed': report =>
      report.providerTimeouts > 0 || report.circuitRejected > 0,
    'inquiry queue and retry concurrency stay bounded': report =>
      report.maxInquiryQueueDepth <= 2 && report.maxRetryInFlight <= 2 && report.retryAttempted > 0,
    'provider work drains after dependency recovery': report =>
      report.commandInFlight === 0 && report.inquiryInFlight === 0 && report.retryInFlight === 0,
  });

  mustStatus(post('/internal/v1/test-support/reset', null, { timeout: '30s' }), 204, 'pre-manual-review reset');
  mustStatus(http.post(`${mockPg}/internal/v1/test-support/reset`, null, params()), 204, 'pre-manual-review PG reset');
  mustStatus(post('/internal/v1/test-support/fault?point=AFTER_PG_BEFORE_DB'), 204, 'fault arm');
  const interruptedAfterPg = approve('chaos-after-pg', 'chaos-after-pg');
  check(interruptedAfterPg, {
    'app interruption after PG leaves an explicit server failure': response => response.status === 500,
  });
  sleep(2);
  const manualReport = get('/internal/v1/recovery/report?staleSeconds=1').json();
  check(manualReport, {
    'ambiguous app interruption is separated for manual review': report => report.manualReviewCount > 0,
  });

  mustStatus(post('/internal/v1/test-support/reset', null, { timeout: '30s' }), 204, 'final payment reset');
  mustStatus(http.post(`${mockPg}/internal/v1/test-support/reset`, null, params()), 204, 'final PG reset');
  const finalApproval = approve('chaos-final', 'chaos-final');
  check(finalApproval, {
    'clean request succeeds after all dependency recovery': response =>
      response.status === 200 && response.json('state') === 'APPROVED',
  });
  const relay = post('/internal/v1/test-support/relay-outbox', null, { timeout: '30s' });
  check(relay, { 'outbox relay succeeds before broker fault phase': response => response.status === 200 });
  sleep(3);
  const consistency = get('/internal/v1/test-support/consistency').json();
  check(consistency, {
    'network fault phase keeps ledger balanced': report => report.ledgerDrift === 0,
    'completed requests leave no processing idempotency': report => report.processingIdempotency === 0,
    'confirmed payments leave no pending confirmation': report => report.pendingConfirmations === 0,
    'published events are consumed exactly once': report =>
      report.processedEvents === report.paymentEventEffects && report.processedEvents > 0,
  });
}

function approve(key, orderId) {
  return post('/api/v1/payments/approve', JSON.stringify({
    userId: `user-${orderId}`,
    merchantId: 'merchant-1',
    orderId,
    amountMinorUnits: 1000,
    currency: 'KRW',
  }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    timeout: '8s',
  });
}

function approvalBatch(prefix, count) {
  const requests = [];
  for (let index = 0; index < count; index += 1) {
    const key = `${prefix}-${index}`;
    requests.push({
      method: 'POST',
      url: `${base}/api/v1/payments/approve`,
      body: JSON.stringify({
        userId: `user-${key}`,
        merchantId: 'merchant-1',
        orderId: `order-${key}`,
        amountMinorUnits: 1000,
        currency: 'KRW',
      }),
      params: {
        ...params('8s'),
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
      },
    });
  }
  return http.batch(requests);
}
function waitForApproval(prefix) {
  let response;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    response = approve(`${prefix}-${attempt}`, `${prefix}-${attempt}`);
    if (response.status === 200 && response.json('state') === 'APPROVED') return response;
    sleep(1);
  }
  return response;
}
function waitForHealth() {
  for (let attempt = 0; attempt < 15; attempt += 1) {
    const response = http.get(`${base}/actuator/health`, params('2s'));
    if (response.status === 200) return;
    sleep(1);
  }
  fail('application did not recover after MySQL proxy was enabled');
}

function setProxy(name, enabled) {
  const response = http.post(
    `${toxiproxy}/proxies/${name}`,
    JSON.stringify({ enabled }),
    params()
  );
  if (response.status !== 200) fail(`proxy ${name} update failed: ${response.status}`);
}

function addToxic(proxy, toxic) {
  const response = http.post(
    `${toxiproxy}/proxies/${proxy}/toxics`,
    JSON.stringify(toxic),
    params()
  );
  if (response.status !== 200) fail(`toxic ${toxic.name} create failed: ${response.status}`);
}

function deleteToxic(proxy, name) {
  const response = http.del(`${toxiproxy}/proxies/${proxy}/toxics/${name}`, null, params());
  if (response.status !== 204 && response.status !== 404) {
    fail(`toxic ${name} delete failed: ${response.status}`);
  }
}

function get(path) {
  return http.get(`${base}${path}`, params());
}

function post(path, body = null, overrides = {}) {
  return http.post(`${base}${path}`, body, { ...params(), ...overrides });
}

function params(timeout = '10s') {
  return {
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'toxiproxy-cli' },
    responseCallback: expected,
    timeout,
  };
}

function mustStatus(response, status, action) {
  if (response.status !== status) fail(`${action} failed: ${response.status}`);
}
