// Scenarios 1 + 2: baseline (no matching rule) and limiter overhead (userId
// rule, every request allowed), at the same constant arrival rate, at the same
// time, each split across both gateways.
//
//   k6 run -e RATE=3000 -e DURATION=60 overhead.js
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { GW1, GW2, TREND_STATS, newRunId, putRule, deleteRule, waitForSync, lat, f, val } from './common.js';

const RATE = Number(__ENV.RATE || 3000); // per scenario (baseline and limited each)
const DURATION = Number(__ENV.DURATION || 60);
const WARMUP = Number(__ENV.WARMUP || 15);
const USERS = Number(__ENV.USERS || 50000);
const TARGET_P95 = 5;
const TARGET_P99 = 10;

const denied = new Counter('denied_429');
const expected200 = http.expectedStatuses(200);

function sc(kind, startTime, duration, execName) {
  return {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: `${duration}s`,
    startTime: `${startTime}s`,
    preAllocatedVUs: 200,
    maxVUs: 2000,
    exec: execName,
    tags: { kind },
  };
}

export const options = {
  scenarios: {
    warm_baseline: sc('warmup', 0, WARMUP, 'baseline'),
    warm_limited: sc('warmup', 0, WARMUP, 'limited'),
    baseline: sc('baseline', WARMUP, DURATION, 'baseline'),
    limited: sc('limited', WARMUP, DURATION, 'limited'),
  },
  summaryTrendStats: TREND_STATS,
  thresholds: {
    'http_req_duration{kind:baseline}': ['max>=0'],
    'http_req_duration{kind:limited}': ['max>=0'],
    'http_reqs{kind:baseline}': ['count>=0'],
    'http_reqs{kind:limited}': ['count>=0'],
    'http_req_failed{kind:baseline}': ['rate==0'],
    'http_req_failed{kind:limited}': ['rate==0'],
    'denied_429{kind:limited}': ['count==0'],
    dropped_iterations: ['count==0'],
  },
};

const RULE_BODY = {
  match: { method: 'GET', path: '/v1/orders/limited/**' },
  keyBy: 'userId',
  capacity: 1000000,
  refillPerSec: 1000,
};

export function setup() {
  const runId = newRunId();
  const ruleId = `lt-overhead-${runId}`;
  putRule(ruleId, RULE_BODY);
  waitForSync('/v1/orders/limited/probe', `probe-${runId}`);
  return { runId, ruleId };
}

export function teardown(d) {
  deleteRule(d.ruleId);
}

function gw() {
  return exec.scenario.iterationInTest % 2 === 0 ? GW1 : GW2;
}

export function baseline() {
  http.get(`${gw()}/v1/orders/baseline/${exec.scenario.iterationInTest % 1000}`, { responseCallback: expected200 });
}

export function limited(d) {
  const i = exec.scenario.iterationInTest;
  const res = http.get(`${gw()}/v1/orders/limited/${i % 1000}`, {
    headers: { 'X-User-Id': `u-${d.runId}-${i % USERS}` },
    responseCallback: expected200,
  });
  if (res.status === 429) denied.add(1);
}

export function handleSummary(data) {
  const b = lat(data, 'http_req_duration{kind:baseline}');
  const l = lat(data, 'http_req_duration{kind:limited}');
  const add95 = l.p95 - b.p95;
  const add99 = l.p99 - b.p99;
  const addAvg = l.avg - b.avg;
  const addP50 = l.p50 - b.p50;
  const d429 = val(data.metrics['denied_429{kind:limited}'], 'count') || 0;
  const dropped = val(data.metrics['dropped_iterations'], 'count') || 0;
  const failB = val(data.metrics['http_req_failed{kind:baseline}'], 'rate');
  const failL = val(data.metrics['http_req_failed{kind:limited}'], 'rate');
  const pass95 = add95 < TARGET_P95;
  const pass99 = add99 < TARGET_P99;
  const clean = d429 === 0 && dropped === 0 && failB === 0 && failL === 0;
  const result = {
    scenario: 'overhead',
    date: new Date().toISOString(),
    ratePerScenarioRps: RATE,
    totalRps: RATE * 2,
    durationSec: DURATION,
    warmupSec: WARMUP,
    baseline: b,
    limited: l,
    added: { p50: addP50, avg: addAvg, p95: add95, p99: add99 },
    targets: { p95Ms: TARGET_P95, p99Ms: TARGET_P99 },
    denied429: d429,
    droppedIterations: dropped,
    failedRate: { baseline: failB, limited: failL },
    pass: { addedP95: pass95, addedP99: pass99, cleanRun: clean },
  };
  const row = (n, x) => `${n.padEnd(9)} n=${String(x.count).padEnd(7)} avg=${f(x.avg)} p50=${f(x.p50)} p95=${f(x.p95)} p99=${f(x.p99)} max=${f(x.max)} ms`;
  const text = [
    `Overhead @ ${RATE} rps per scenario (${RATE * 2} rps total), ${DURATION}s after ${WARMUP}s warm-up`,
    row('baseline', b),
    row('limited', l),
    `added: p50=${f(addP50)} avg=${f(addAvg)} p95=${f(add95)} (target <${TARGET_P95}) ${pass95 ? 'PASS' : 'FAIL'}  p99=${f(add99)} (target <${TARGET_P99}) ${pass99 ? 'PASS' : 'FAIL'} ms`,
    `429s=${d429} dropped=${dropped} failed(baseline)=${f(failB, 4)} failed(limited)=${f(failL, 4)} clean=${clean}`,
    '',
  ].join('\n');
  const out = __ENV.OUT || `/results/overhead-${RATE}.json`;
  return { stdout: text, [out]: JSON.stringify(result, null, 2) };
}
