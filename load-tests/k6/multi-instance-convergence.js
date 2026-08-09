import http from 'k6/http';
import { check } from 'k6';

const first = __ENV.BASE_URL_1 || 'http://host.docker.internal:8081';
const second = __ENV.BASE_URL_2 || 'http://host.docker.internal:8082';
const bases = [first, second];
const stormSize = Number(__ENV.STORM_SIZE || 100);
const bulkSize = Number(__ENV.BULK_SIZE || 40);
const jsonHeaders = { 'Content-Type': 'application/json' };
const successOrConflict = http.expectedStatuses(200, 409, 429);
const successOrShed = http.expectedStatuses(200, 429);

export const options = {
  scenarios: {
    convergence: { executor: 'shared-iterations', vus: 1, iterations: 1 }
  },
  batchPerHost: stormSize,
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    http_req_duration: ['p(95)<3000']
  }
};

function post(base, path, body = null, params = {}) {
  return http.post(`${base}${path}`, body, params);
}

function approvalBody(orderId) {
  return JSON.stringify({
    userId: 'multi-user',
    merchantId: 'merchant-1',
    orderId,
    amountMinorUnits: 1000,
    currency: 'KRW'
  });
}

function approvalRequest(base, orderId, key, expectedStatuses) {
  return [
    'POST',
    `${base}/api/v1/payments/approve`,
    approvalBody(orderId),
    {
      headers: { ...jsonHeaders, 'Idempotency-Key': key },
      timeout: '10s',
      responseCallback: expectedStatuses
    }
  ];
}

export function setup() {
  for (const base of [second, first]) {
    const reset = post(base, '/internal/v1/test-support/reset', null, { timeout: '30s' });
    if (reset.status !== 204) throw new Error(`reset failed for ${base}: ${reset.status}`);
    const delay = post(base, '/internal/v1/test-support/pg-delay?millis=300');
    if (delay.status !== 204) throw new Error(`PG delay failed for ${base}: ${delay.status}`);
  }
}

export default function () {
  const stormRequests = Array.from({ length: stormSize }, (_, index) => approvalRequest(
    bases[index % bases.length],
    'multi-hot-order',
    'multi-hot-key',
    successOrConflict
  ));
  const stormResponses = http.batch(stormRequests);
  check(stormResponses, {
    'one approval wins across instances': responses => responses.filter(r => r.status === 200).length === 1,
    'other concurrent requests are controlled': responses => responses.every(r => [200, 409, 429].includes(r.status))
  });

  const replayResponses = bases.map(base => {
    const [method, url, body, params] = approvalRequest(
      base,
      'multi-hot-order',
      'multi-hot-key',
      http.expectedStatuses(200)
    );
    return http.request(method, url, body, params);
  });
  check(replayResponses, {
    'both instances reuse the same response without session affinity': responses =>
      responses.every(r => r.status === 200 && r.headers['Idempotency-Replayed'] === 'true') &&
      responses[0].json('paymentId') === responses[1].json('paymentId')
  });

  bases.forEach(base => post(base, '/internal/v1/test-support/pg-delay?millis=0'));
  const bulkResponses = http.batch(Array.from({ length: bulkSize }, (_, index) => approvalRequest(
    bases[index % bases.length],
    `multi-order-${index}`,
    `multi-key-${index}`,
    successOrShed
  )));
  const bulkAccepted = bulkResponses.filter(response => response.status === 200).length;
  check(bulkResponses, {
    'distributed unique approvals are processed or shed': responses =>
      bulkAccepted > 0 && responses.every(r => [200, 429].includes(r.status))
  });

  const relayResponses = http.batch(bases.map(base => [
    'POST',
    `${base}/internal/v1/test-support/relay-outbox-once?limit=${bulkSize / 2}`,
    null,
    { timeout: '30s' }
  ]));
  const workerPublished = relayResponses.map(response => response.json('published'));
  check(relayResponses, {
    'concurrent outbox workers do not fail': responses =>
      responses.every(response => response.status === 200)
  });
  workerPublished.forEach((published, index) => {
    if (published === 0) {
      workerPublished[index] += post(
        bases[index],
        `/internal/v1/test-support/relay-outbox-once?limit=${bulkSize / 2}`,
        null,
        { timeout: '30s' }
      ).json('published');
    }
  });
  check(workerPublished, {
    'both outbox workers eventually publish a disjoint batch': counts =>
      counts.every(count => count > 0)
  });
  post(first, '/internal/v1/test-support/relay-outbox', null, { timeout: '30s' });

  const reports = bases.map(base => http.get(`${base}/internal/v1/test-support/multi-instance`).json());
  const protectionReports = bases.map(base => http.get(`${base}/internal/v1/test-support/overload`).json());
  const totalPgCalls = reports.reduce((sum, report) => sum + report.approveCalls, 0);
  const totalPublishedCalls = reports.reduce((sum, report) => sum + report.outboxPublished, 0);
  const totalClaimed = reports.reduce((sum, report) => sum + report.outboxClaimed, 0);
  const totalGateRejected = reports.reduce((sum, report) => sum + report.gateRejected, 0);
  const totalAdmissionRejected = protectionReports.reduce(
    (sum, report) => sum + report.admissionRejected,
    0
  );
  const totalOutbox = bulkAccepted + 1;
  check(reports, {
    'both application instances handled PG work': values => values.every(value => value.approveCalls > 0),
    'provider side effects match unique payments': () => totalPgCalls === totalOutbox,
    'outbox publisher side effects are not duplicated': () => totalPublishedCalls === totalOutbox,
    'outbox claims match published events': () => totalClaimed === totalOutbox,
    'distributed admission controls the cross-instance storm': () =>
      totalGateRejected + totalAdmissionRejected >= stormSize - 1,
    'database converged all outbox events': values =>
      values[0].outboxTotal === totalOutbox &&
      values[0].outboxPublishedRows === totalOutbox &&
      values[0].outboxPendingRows === 0 &&
      values[0].outboxClaimedRows === 0
  });

  const consistency = http.get(`${first}/internal/v1/test-support/consistency`).json();
  check(consistency, {
    'multi-instance flow keeps payment consistency': report =>
      Object.values(report).every(value => value === 0)
  });

  console.log(`MULTI_INSTANCE_REPORT ${JSON.stringify({
    reports,
    protectionReports,
    bulkAccepted,
    totalPgCalls,
    totalPublishedCalls,
    totalClaimed
  })}`);
}

export function teardown() {
  bases.forEach(base => post(base, '/internal/v1/test-support/pg-delay?millis=0'));
}
