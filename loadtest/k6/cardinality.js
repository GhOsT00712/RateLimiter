// Scenario 6: key cardinality. IDS distinct identities under a per-user rule, one request each,
// alternating between both gateways. Redis memory / dbsize and gateway memory are read by
// loadtest/run.sh around this run.
//
//   k6 run -e IDS=100000 -e RULE=long cardinality.js
// RULE=long:  capacity 10, refill 1/30 per s  -> key TTL 300 s (keys still there when measured)
// RULE=short: capacity 10, refill 1 per s     -> key TTL 10 s  (keys must expire)
import http from 'k6/http';
import exec from 'k6/execution';
import { GW1, GW2, TREND_STATS, newRunId, putRule, deleteRule, waitForSync, lat, f, val } from './common.js';

const IDS = Number(__ENV.IDS || 100000);
const RATE = Number(__ENV.RATE || 8000);
const RULE = __ENV.RULE || 'long';
const REFILL = RULE === 'short' ? 1 : 1 / 30;
const expected = http.expectedStatuses(200, 429);

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    ids: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${Math.ceil(IDS / RATE)}s`,
      preAllocatedVUs: 400, maxVUs: 3000,
    },
  },
  thresholds: {
    http_req_duration: ['max>=0'],
    dropped_iterations: ['count>=0'],
    http_req_failed: ['rate>=0'],
    'http_reqs{status:429}': ['count>=0'],
  },
};

export function setup() {
  const runId = newRunId();
  const ruleId = `lt-card-${RULE}-${runId}`;
  putRule(ruleId, { match: { method: 'GET', path: '/v1/orders/card/**' }, keyBy: 'userId', capacity: 10, refillPerSec: REFILL });
  waitForSync('/v1/orders/card/probe', `probe-${runId}`);
  return { runId, ruleId };
}

export function teardown(d) {
  deleteRule(d.ruleId);
}

export default function (d) {
  const i = exec.scenario.iterationInTest;
  if (i >= IDS) return; // the arrival-rate executor may start a few more than IDS
  const gw = i % 2 === 0 ? GW1 : GW2;
  http.get(`${gw}/v1/orders/card/${i % 1000}`, {
    headers: { 'X-User-Id': `usr-${d.runId}-${i}` },
    responseCallback: expected,
  });
}

export function handleSummary(data) {
  const l = lat(data, 'http_req_duration');
  const dropped = val(data.metrics['dropped_iterations'], 'count') || 0;
  const result = {
    scenario: 'cardinality', date: new Date().toISOString(), ids: IDS, rule: RULE, rateRps: RATE,
    ttlSec: 10 / REFILL, latency: l, droppedIterations: dropped,
    failedRate: val(data.metrics['http_req_failed'], 'rate'), denied429: val(data.metrics['http_reqs{status:429}'], 'count') || 0,
  };
  const text = `Cardinality ${IDS} ids (${RULE} TTL ${f(10 / REFILL, 0)}s) @ ${RATE} rps: n=${l.count} p50=${f(l.p50)} p95=${f(l.p95)} p99=${f(l.p99)} max=${f(l.max)} ms dropped=${dropped} failed=${f(result.failedRate, 5)} 429=${result.denied429}\n`;
  const out = __ENV.OUT || `/results/cardinality-${RULE}-${IDS}.json`;
  return { stdout: text, [out]: JSON.stringify(result, null, 2) };
}
