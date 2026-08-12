import fs from 'node:fs';
import path from 'node:path';

const outputPath = process.argv[2] || 'docs/history/evidence/step21-partition-simulation.json';
const recordCount = Number(process.env.STEP21_RECORDS || 100000);
const partitionCounts = [4, 6, 8, 12];
const profileNames = ['uniform', 'merchant-80-20', 'single-hot', 'burst'];

function hash(value) {
  let result = 0x811c9dc5;
  for (const byte of Buffer.from(value, 'utf8')) {
    result ^= byte;
    result = Math.imul(result, 0x01000193);
  }
  return result >>> 0;
}

function merchantFor(profile, index) {
  if (profile === 'uniform') return `merchant-${(index % 100) + 1}`;
  if (profile === 'merchant-80-20') {
    return index % 10 < 8 ? 'merchant-1' : `merchant-${(index % 99) + 2}`;
  }
  if (profile === 'single-hot') {
    return index % 20 < 19 ? 'merchant-1' : `merchant-${(index % 99) + 2}`;
  }
  return index < recordCount * 0.8
    ? `merchant-${(index % 100) + 1}`
    : 'merchant-1';
}

function timeBucketFor(profile, index) {
  if (profile !== 'burst') return index % 100;
  return index < recordCount * 0.8 ? index % 100 : 100;
}

function summarize(values) {
  const total = values.reduce((sum, value) => sum + value, 0);
  const mean = total / values.length;
  const max = Math.max(...values);
  const variance = values.reduce((sum, value) => sum + ((value - mean) ** 2), 0) / values.length;
  return {
    counts: values,
    maxToMeanRatio: Number((max / mean).toFixed(6)),
    hotPartitionShare: Number((max / total).toFixed(6)),
    coefficientOfVariation: Number((Math.sqrt(variance) / mean).toFixed(6)),
  };
}

function candidateKey(candidate, record) {
  switch (candidate) {
    case 'merchantId': return record.merchantId;
    case 'orderIdHash': return record.orderId;
    case 'paymentIdHash': return record.paymentId;
    case 'kafkaAggregateKey': return record.paymentId;
    case 'outboxPartitionKey': return record.paymentId;
    default: throw new Error(`Unknown candidate: ${candidate}`);
  }
}

const candidates = [
  'merchantId',
  'orderIdHash',
  'paymentIdHash',
  'kafkaAggregateKey',
  'outboxPartitionKey',
];

const profiles = profileNames.map(profile => {
  const records = Array.from({ length: recordCount }, (_, index) => ({
    merchantId: merchantFor(profile, index),
    orderId: `order-${profile}-${index}`,
    paymentId: `payment-${profile}-${index}`,
    timeBucket: timeBucketFor(profile, index),
  }));
  const bucketCounts = new Map();
  for (const record of records) {
    bucketCounts.set(record.timeBucket, (bucketCounts.get(record.timeBucket) || 0) + 1);
  }
  const temporal = summarize([...bucketCounts.values()]);
  const candidateResults = {};

  for (const candidate of candidates) {
    const distributions = {};
    for (const partitions of partitionCounts) {
      const counts = Array(partitions).fill(0);
      for (const record of records) {
        counts[hash(candidateKey(candidate, record)) % partitions] += 1;
      }
      distributions[partitions] = summarize(counts);
    }

    let remapped6To12 = 0;
    let remapped6To8 = 0;
    const merchantPartitions = new Map();
    for (const record of records) {
      const hashed = hash(candidateKey(candidate, record));
      if ((hashed % 6) !== (hashed % 12)) remapped6To12 += 1;
      if ((hashed % 6) !== (hashed % 8)) remapped6To8 += 1;
      if (!merchantPartitions.has(record.merchantId)) merchantPartitions.set(record.merchantId, new Set());
      merchantPartitions.get(record.merchantId).add(hashed % 6);
    }
    const merchantFanouts = [...merchantPartitions.values()].map(value => value.size);
    candidateResults[candidate] = {
      distributions,
      remapRate6To8: Number((remapped6To8 / recordCount).toFixed(6)),
      remapRate6To12: Number((remapped6To12 / recordCount).toFixed(6)),
      reconciliationFanoutAt6: {
        average: Number((merchantFanouts.reduce((sum, value) => sum + value, 0) / merchantFanouts.length).toFixed(6)),
        maximum: Math.max(...merchantFanouts),
      },
    };
  }

  return {
    profile,
    recordCount,
    temporalPeakToMeanRatio: temporal.maxToMeanRatio,
    candidates: candidateResults,
  };
});

const report = {
  generatedAt: new Date().toISOString(),
  recordCount,
  hash: 'FNV-1a 32-bit',
  partitionCounts,
  profiles,
  routingConstraints: {
    merchantId: 'Approval idempotency scope is available, but a hot merchant remains on one shard.',
    orderIdHash: 'Approval can route, but cancellation needs an order-to-payment routing lookup.',
    paymentIdHash: 'Payment aggregate is colocated, but approval idempotency needs a global or separately partitioned index.',
    kafkaAggregateKey: 'Preserves per-payment event order; it is not a DB shard key by itself.',
    outboxPartitionKey: 'Matches aggregate ordering; partition count changes remap future records.',
  },
};

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, JSON.stringify(report, null, 2));
console.log(`Step 21 partition simulation: ${outputPath}`);