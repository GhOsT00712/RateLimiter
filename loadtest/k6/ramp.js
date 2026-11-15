// Scenario 3: capacity ramp against ONE gateway (gateway-1) on the limited
// path. Each step is its own constant-arrival-rate scenario so every metric,
// including dropped_iterations, carries a step tag.
//
//   k6 run -e START=2000 -e STEP=2000 -e STEPS=10 -e STEP_SEC=20 ramp.js
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { GW1, TREND_STATS, newRunId, putRule, deleteRule, waitForSync, lat, f, val } from './common.js';

const START = Number(__ENV.START || 2000);
const STEP = Number(__ENV.STEP || 2000);
const STEPS = Number(__ENV.STEPS || 10);
const STEP_SEC = Number(__ENV.STEP_SEC || 20);
const WARMUP = Number(__ENV.WARMUP || 15);
const CEILING_MS = Number(__ENV.CEILING_MS || 50);
const USERS = Number(__ENV.USERS || 100000);

const denied = new Counter('denied_429');
const expected200 = http.expectedStatuses(200);
const name = (i) => `s${String(i + 1).padStart(2, '0')}`;

const scenarios = {
  warmup: {
    executor: 'constant-arrival-rate', rate: START, timeUnit: '1s', duration: `${WARMUP}s`,
    preAllocatedVUs: 300, maxVUs: 3000, exec: 'hit', tags: { step: 'warmup' },
  },
};
const thresholds = { dropped_iterations: ['count>=0'] };
for (let i = 0; i < STEPS; i++) {
  const n = name(i);
  scenarios[n] = {
    executor: 'constant-arrival-rate',
    rate: START + i * STEP,
    timeUnit: '1s',
    duration: `${STEP_SEC}s`,
    startTime: `${WARMUP + i * STEP_SEC}s`,
    preAllocatedVUs: 300,
    maxVUs: 3000,
    exec: 'hit',
    tags: { step: n },
  };
  thresholds[`http_req_duration{step:${n}}`] = ['max>=0'];
  thresholds[`http_reqs{step:${n}}`] = ['count>=0'];
  thresholds[`http_req_failed{step:${n}}`] = ['rate>=0'];
  thresholds[`dropped_iterations{step:${n}}`] = ['count>=0'];
  thresholds[`denied_429{step:${n}}`] = ['count>=0'];
}

export const options = { scenarios, thresholds, summaryTrendStats: TREND_STATS };

export function setup() {
  const runId = newRunId();
  const ruleId = `lt-ramp-${runId}`;
  putRule(ruleId, {
    match: { method: 'GET', path: '/v1/orders/limited/**' },
    keyBy: 'userId',
    capacity: 1000000,
    refillPerSec: 1000,
  });
  waitForSync('/v1/orders/limited/probe', `probe-${runId}`);
  return { runId, ruleId };
}

export function teardown(d) {
  deleteRule(d.ruleId);
}

export function hit(d) {
  const i = exec.scenario.iterationInTest;
  const res = http.get(`${GW1}/v1/orders/limited/${i % 1000}`, {
    headers: { 'X-User-Id': `u-${d.runId}-${i % USERS}` },
    responseCallback: expected200,
  });
  if (res.status === 429) denied.add(1);
}

export function handleSummary(data) {
  const steps = [];
  let firstBad = null;
  for (let i = 0; i < STEPS; i++) {
    const n = name(i);
    const target = START + i * STEP;
    const l = lat(data, `http_req_duration{step:${n}}`);
    const dropped = val(data.metrics[`dropped_iterations{step:${n}}`], 'count') || 0;
    const failRate = val(data.metrics[`http_req_failed{step:${n}}`], 'rate') || 0;
    const d429 = val(data.metrics[`denied_429{step:${n}}`], 'count') || 0;
    const achieved = (l.count || 0) / STEP_SEC;
    const reasons = [];
    if (l.p99 === undefined || l.p99 > CEILING_MS) reasons.push(`p99>${CEILING_MS}ms`);
    if (dropped > 0) reasons.push(`dropped=${dropped}`);
    if (failRate > 0) reasons.push(`failed=${f(failRate, 4)}`);
    if (achieved < target * 0.98) reasons.push(`achieved<98%`);
    const healthy = reasons.length === 0;
    if (!healthy && firstBad === null) firstBad = i;
    steps.push({ step: n, targetRps: target, achievedRps: achieved, ...l, dropped, failRate, denied429: d429, healthy, reasons });
  }
  const lastGood = firstBad === null ? STEPS - 1 : firstBad - 1;
  const result = {
    scenario: 'ramp',
    date: new Date().toISOString(),
    gateway: 'gateway-1 only',
    stepSec: STEP_SEC,
    ceilingP99Ms: CEILING_MS,
    steps,
    maxHealthyRps: lastGood >= 0 ? steps[lastGood].targetRps : null,
    maxHealthyAchievedRps: lastGood >= 0 ? steps[lastGood].achievedRps : null,
    ceilingReached: firstBad !== null,
  };
  const lines = [`Ramp on gateway-1, ${STEP_SEC}s steps, p99 ceiling ${CEILING_MS} ms`];
  for (const s of steps) {
    lines.push(`${s.step} target=${String(s.targetRps).padEnd(6)} got=${f(s.achievedRps, 0).padEnd(6)} p50=${f(s.p50)} p95=${f(s.p95)} p99=${f(s.p99)} max=${f(s.max)} dropped=${s.dropped} fail=${f(s.failRate, 4)} ${s.healthy ? 'ok' : 'BAD ' + s.reasons.join(',')}`);
  }
  lines.push(`max healthy step: ${result.maxHealthyRps} rps (ceiling reached: ${result.ceilingReached})`, '');
  const out = __ENV.OUT || '/results/ramp.json';
  return { stdout: lines.join('\n'), [out]: JSON.stringify(result, null, 2) };
}
