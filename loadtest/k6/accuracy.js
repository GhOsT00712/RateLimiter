// Scenario 4: accuracy across two gateways.
//  4a: one identity, requests alternating between gateways, far above the limit.
//  4b: 1,000 identities with a small capacity and negligible refill.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { GW1, GW2, TREND_STATS, newRunId, putRule, deleteRule, waitForSync, f, val } from './common.js';

const A_CAP = Number(__ENV.A_CAP || 100);
const A_REFILL = Number(__ENV.A_REFILL || 10);
const A_RATE = Number(__ENV.A_RATE || 2000);
const A_SEC = Number(__ENV.A_SEC || 30);
const B_IDS = 1000;
const B_CAP = Number(__ENV.B_CAP || 5);
const B_REFILL = Number(__ENV.B_REFILL || 0.001);
const B_RATE = Number(__ENV.B_RATE || 3000);
const B_SEC = Number(__ENV.B_SEC || 30);
const GAP = 5;

const okA = new Counter('a_allowed');
const deniedA = new Counter('a_denied');
const tStartA = new Trend('a_t_start_ms');
const tEndA = new Trend('a_t_end_ms');
const okB = new Counter('b_allowed');
const deniedB = new Counter('b_denied');
const tStartB = new Trend('b_t_start_ms');
const tEndB = new Trend('b_t_end_ms');
const perId = [];
for (let i = 0; i < B_IDS; i++) perId.push(new Counter(`b_allowed_id_${i}`));

const expected = http.expectedStatuses(200, 429);

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    single: {
      executor: 'constant-arrival-rate', rate: A_RATE, timeUnit: '1s', duration: `${A_SEC}s`,
      preAllocatedVUs: 200, maxVUs: 2000, exec: 'single',
    },
    many: {
      executor: 'constant-arrival-rate', rate: B_RATE, timeUnit: '1s', duration: `${B_SEC}s`,
      startTime: `${A_SEC + GAP}s`, preAllocatedVUs: 200, maxVUs: 2000, exec: 'many',
    },
  },
  thresholds: { dropped_iterations: ['count>=0'], http_req_failed: ['rate>=0'] },
};

export function setup() {
  const runId = newRunId();
  const ruleA = `lt-acc-single-${runId}`;
  const ruleB = `lt-acc-many-${runId}`;
  putRule(ruleA, { match: { method: 'GET', path: '/v1/orders/accsingle/**' }, keyBy: 'userId', capacity: A_CAP, refillPerSec: A_REFILL });
  putRule(ruleB, { match: { method: 'GET', path: '/v1/orders/accmany/**' }, keyBy: 'userId', capacity: B_CAP, refillPerSec: B_REFILL });
  waitForSync('/v1/orders/accsingle/probe', `probe-a-${runId}`);
  waitForSync('/v1/orders/accmany/probe', `probe-b-${runId}`);
  return { runId, ruleA, ruleB };
}

export function teardown(d) {
  deleteRule(d.ruleA);
  deleteRule(d.ruleB);
}

export function single(d) {
  const i = exec.scenario.iterationInTest;
  const gw = i % 2 === 0 ? GW1 : GW2;
  const t0 = Date.now();
  const res = http.get(`${gw}/v1/orders/accsingle/x`, {
    headers: { 'X-User-Id': `single-${d.runId}` },
    responseCallback: expected,
  });
  const t1 = Date.now();
  tStartA.add(t0);
  tEndA.add(t1);
  if (res.status === 200) okA.add(1);
  else if (res.status === 429) deniedA.add(1);
}

export function many(d) {
  const i = exec.scenario.iterationInTest;
  const gw = i % 2 === 0 ? GW1 : GW2;
  const id = Math.floor(i / 2) % B_IDS; // each identity alternates gateways
  const t0 = Date.now();
  const res = http.get(`${gw}/v1/orders/accmany/x`, {
    headers: { 'X-User-Id': `m-${d.runId}-${id}` },
    responseCallback: expected,
  });
  const t1 = Date.now();
  tStartB.add(t0);
  tEndB.add(t1);
  if (res.status === 200) { okB.add(1); perId[id].add(1); }
  else if (res.status === 429) deniedB.add(1);
}

export function handleSummary(data) {
  // 4a
  const allowedA = val(data.metrics['a_allowed'], 'count') || 0;
  const deniedACount = val(data.metrics['a_denied'], 'count') || 0;
  const elapsedA = (val(data.metrics['a_t_end_ms'], 'max') - val(data.metrics['a_t_start_ms'], 'min')) / 1000;
  const boundA = A_CAP + A_REFILL * elapsedA;
  const passA = allowedA <= boundA + 1;
  // 4b
  let maxPerId = 0, idsSeen = 0, atCap = 0;
  for (let i = 0; i < B_IDS; i++) {
    const c = val(data.metrics[`b_allowed_id_${i}`], 'count') || 0;
    if (c > 0) idsSeen++;
    if (c > maxPerId) maxPerId = c;
    if (c >= B_CAP) atCap++;
  }
  const allowedB = val(data.metrics['b_allowed'], 'count') || 0;
  const deniedBCount = val(data.metrics['b_denied'], 'count') || 0;
  const elapsedB = (val(data.metrics['b_t_end_ms'], 'max') - val(data.metrics['b_t_start_ms'], 'min')) / 1000;
  const perIdBound = Math.floor(B_CAP + B_REFILL * elapsedB + 1e-9);
  let violating = 0;
  for (let i = 0; i < B_IDS; i++) {
    if ((val(data.metrics[`b_allowed_id_${i}`], 'count') || 0) > perIdBound) violating++;
  }
  const theoreticalB = B_IDS * (B_CAP + B_REFILL * elapsedB);
  const passB = violating === 0;
  const dropped = val(data.metrics['dropped_iterations'], 'count') || 0;
  const result = {
    scenario: 'accuracy',
    date: new Date().toISOString(),
    single: {
      capacity: A_CAP, refillPerSec: A_REFILL, ratePerSec: A_RATE, durationSec: A_SEC,
      allowed: allowedA, denied: deniedACount, elapsedSec: elapsedA, bound: boundA,
      allowedOverBound: allowedA / boundA, headroom: boundA - allowedA, pass: passA,
    },
    many: {
      identities: B_IDS, capacity: B_CAP, refillPerSec: B_REFILL, ratePerSec: B_RATE, durationSec: B_SEC,
      allowed: allowedB, denied: deniedBCount, elapsedSec: elapsedB,
      perIdentityBound: perIdBound, maxAllowedForOneIdentity: maxPerId,
      identitiesOverBound: violating, identitiesAtCapacity: atCap, identitiesSeen: idsSeen,
      theoreticalMaxTotal: theoreticalB, allowedOverTheoretical: allowedB / theoreticalB, pass: passB,
    },
    droppedIterations: dropped,
  };
  const text = [
    `4a single identity, cap=${A_CAP} refill=${A_REFILL}/s, ${A_RATE} rps for ${A_SEC}s across 2 gateways`,
    `   allowed=${allowedA} denied=${deniedACount} elapsed=${f(elapsedA, 3)}s bound=${f(boundA, 2)} -> ${(allowedA / boundA * 100).toFixed(2)}% of bound, ${passA ? 'PASS' : 'FAIL'}`,
    `4b ${B_IDS} identities, cap=${B_CAP} refill=${B_REFILL}/s, ${B_RATE} rps for ${B_SEC}s across 2 gateways`,
    `   allowed=${allowedB} (theoretical max ${f(theoreticalB, 2)}) denied=${deniedBCount} max/identity=${maxPerId} over-bound=${violating} at-capacity=${atCap}/${B_IDS} -> ${passB ? 'PASS' : 'FAIL'}`,
    `dropped iterations=${dropped}`,
    '',
  ].join('\n');
  const out = __ENV.OUT || '/results/accuracy.json';
  return { stdout: text, [out]: JSON.stringify(result, null, 2) };
}
