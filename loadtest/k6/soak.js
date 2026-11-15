// Scenario 9: soak. RATE rps per gateway (both gateways), half cold keys, half hot keys, for DURATION seconds.
// Host-side sampling (docker stats, Redis memory, JVM heap) is done by loadtest/run.sh.
//
//   k6 run -e RATE=3000 -e DURATION=1200 soak.js
// cold: 100,000 identities cycled, capacity 100 refill 5/s -> key TTL 20 s, so keys keep expiring and being recreated
// hot:  50 identities, capacity 1,000,000 refill 1000/s -> leasing active, nothing denied
import http from 'k6/http';
import exec from 'k6/execution';
import { GW1, GW2, TREND_STATS, newRunId, putRule, deleteRule, waitForSync, val, f } from './common.js';
import { rec, tlThresholds, tlSummary, phaseLine } from './timeline.js';

const RATE = Number(__ENV.RATE || 3000); // per gateway
const DURATION = Number(__ENV.DURATION || 1200);
const MINUTES = Math.ceil(DURATION / 60);
const COLD_IDS = 100000;
const HOT_IDS = 50;
const expected = http.expectedStatuses(200, 429);

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate', rate: RATE * 2, timeUnit: '1s', duration: `${DURATION}s`,
      preAllocatedVUs: 600, maxVUs: 4000,
    },
  },
  thresholds: {
    ...tlThresholds(['cold', 'hot'], MINUTES + 1, ['all']),
    dropped_iterations: ['count>=0'],
    http_req_failed: ['rate>=0'],
  },
};

export function setup() {
  const runId = newRunId();
  const cold = `lt-soak-cold-${runId}`;
  const hot = `lt-soak-hot-${runId}`;
  putRule(cold, { match: { method: 'GET', path: '/v1/orders/soakc/**' }, keyBy: 'userId', capacity: 100, refillPerSec: 5 });
  putRule(hot, { match: { method: 'GET', path: '/v1/orders/soakh/**' }, keyBy: 'userId', capacity: 1000000, refillPerSec: 1000 });
  waitForSync('/v1/orders/soakc/probe', `probe-c-${runId}`);
  waitForSync('/v1/orders/soakh/probe', `probe-h-${runId}`);
  const t0 = Date.now();
  console.log(`T0 ${t0}`);
  return { runId, cold, hot, t0 };
}

export function teardown(d) {
  deleteRule(d.cold);
  deleteRule(d.hot);
}

export default function (d) {
  const i = exec.scenario.iterationInTest;
  const gw = i % 2 === 0 ? GW1 : GW2;
  const isCold = Math.floor(i / 2) % 2 === 0;
  const n = Math.floor(i / 4);
  const id = isCold ? `c-${d.runId}-${n % COLD_IDS}` : `h-${d.runId}-${n % HOT_IDS}`;
  const res = http.get(`${gw}/v1/orders/${isCold ? 'soakc' : 'soakh'}/${i % 1000}`, {
    headers: { 'X-User-Id': id },
    responseCallback: expected,
  });
  rec(isCold ? 'cold' : 'hot', Math.floor((Date.now() - d.t0) / 60000), 'all', res);
}

export function handleSummary(data) {
  const t = tlSummary(data, ['cold', 'hot'], MINUTES + 1, ['all']);
  const dropped = val(data.metrics['dropped_iterations'], 'count') || 0;
  const result = {
    scenario: 'soak', date: new Date().toISOString(), ratePerGatewayRps: RATE, durationSec: DURATION,
    all: t.perPhase, perMinute: t.perSec, droppedIterations: dropped, failedRate: val(data.metrics['http_req_failed'], 'rate'),
  };
  const lines = [`Soak ${RATE} rps per gateway x2, ${DURATION}s`, phaseLine('cold (all)', t.perPhase.cold.all), phaseLine('hot (all)', t.perPhase.hot.all)];
  for (const g of ['cold', 'hot']) for (const r of t.perSec[g]) lines.push(`${g} min ${r.s}: n=${r.n} 429=${r.n429} other=${r.noth} p50=${f(r.p50)} p95=${f(r.p95)} p99=${f(r.p99)} max=${f(r.max)}`);
  lines.push(`dropped iterations=${dropped} failed rate=${f(result.failedRate, 5)}`, '');
  const out = __ENV.OUT || '/results/soak.json';
  return { stdout: lines.join('\n'), [out]: JSON.stringify(result) };
}
