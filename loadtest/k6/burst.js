// Scenario 5: burst on ONE gateway (gateway-1). LOW_PCT of CAP rps, a jump to CAP rps within one second,
// held HOLD_SEC, then back to LOW_PCT.
//
//   k6 run -e CAP=6000 -e MIX=cold burst.js
// MIX=cold: many distinct users (low-traffic keys, one Redis call per request)
// MIX=hot:  50 identities (leasing active)
//
// Phases (tag ph): before | burst_first5 | burst_rest | after
import http from 'k6/http';
import exec from 'k6/execution';
import { GW1, TREND_STATS, newRunId, putRule, deleteRule, waitForSync, f, val } from './common.js';
import { rec, tlThresholds, tlSummary, phaseLine } from './timeline.js';

const CAP = Number(__ENV.CAP || 6000);
const LOW_PCT = Number(__ENV.LOW_PCT || 10);
const MIX = __ENV.MIX || 'cold';
const PRE = Number(__ENV.PRE || 20);
const HOLD = Number(__ENV.HOLD || 30);
const POST = Number(__ENV.POST || 20);
const LOW = Math.round((CAP * LOW_PCT) / 100);
const PATH = MIX === 'hot' ? 'hot' : 'limited';
const USERS = MIX === 'hot' ? 50 : 100000;
const FIRST = 5;
const TOTAL = PRE + HOLD + 1 + POST;
const PHASES = ['before', 'burst_first5', 'burst_rest', 'after'];
const SCEN_PHASE = { before: 'before', burst_first5: 'burst_first5', burst_rest: 'burst_rest', ramp_down: 'after', after: 'after' };
const expected = http.expectedStatuses(200, 429);

const vus = { preAllocatedVUs: 400, maxVUs: 3000, exec: 'hit' };
export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    before: { executor: 'constant-arrival-rate', rate: LOW, timeUnit: '1s', duration: `${PRE}s`, ...vus },
    // 1 s linear jump from LOW to CAP, then the rest of the first 5 s of the burst
    burst_first5: {
      executor: 'ramping-arrival-rate', startRate: LOW, timeUnit: '1s', startTime: `${PRE}s`,
      stages: [{ duration: '1s', target: CAP }, { duration: `${FIRST - 1}s`, target: CAP }], ...vus,
    },
    burst_rest: {
      executor: 'constant-arrival-rate', rate: CAP, timeUnit: '1s', duration: `${HOLD - FIRST}s`,
      startTime: `${PRE + FIRST}s`, ...vus,
    },
    ramp_down: {
      executor: 'ramping-arrival-rate', startRate: CAP, timeUnit: '1s', startTime: `${PRE + HOLD}s`,
      stages: [{ duration: '1s', target: LOW }], ...vus,
    },
    after: { executor: 'constant-arrival-rate', rate: LOW, timeUnit: '1s', duration: `${POST}s`, startTime: `${PRE + HOLD + 1}s`, ...vus },
  },
  thresholds: {
    ...tlThresholds(['b'], TOTAL + 2, PHASES),
    ...Object.fromEntries(Object.keys(SCEN_PHASE).map((s) => [`dropped_iterations{scenario:${s}}`, ['count>=0']])),
    dropped_iterations: ['count>=0'],
    http_req_failed: ['rate>=0'],
  },
};

export function setup() {
  const runId = newRunId();
  const ruleId = `lt-burst-${MIX}-${runId}`;
  putRule(ruleId, { match: { method: 'GET', path: `/v1/orders/${PATH}/**` }, keyBy: 'userId', capacity: 1000000, refillPerSec: 1000 });
  waitForSync(`/v1/orders/${PATH}/probe`, `probe-${runId}`);
  return { runId, ruleId, t0: Date.now() };
}

export function teardown(d) {
  deleteRule(d.ruleId);
}

export function hit(d) {
  const res = http.get(`${GW1}/v1/orders/${PATH}/${Math.floor(Math.random() * 1000)}`, {
    headers: { 'X-User-Id': `u-${d.runId}-${Math.floor(Math.random() * USERS)}` },
    responseCallback: expected,
  });
  const sec = Math.floor((Date.now() - d.t0) / 1000);
  rec('b', sec, SCEN_PHASE[exec.scenario.name], res);
}

export function handleSummary(data) {
  const t = tlSummary(data, ['b'], TOTAL + 2, PHASES);
  const dropped = {};
  for (const s of Object.keys(SCEN_PHASE)) dropped[s] = val(data.metrics[`dropped_iterations{scenario:${s}}`], 'count') || 0;
  const droppedTotal = val(data.metrics['dropped_iterations'], 'count') || 0;
  const result = {
    scenario: 'burst', date: new Date().toISOString(), mix: MIX, capRps: CAP, lowRps: LOW, holdSec: HOLD,
    phases: t.perPhase.b, perSec: t.perSec.b, droppedByScenario: dropped, droppedTotal,
    failedRate: val(data.metrics['http_req_failed'], 'rate'),
  };
  const lines = [`Burst (${MIX} keys) on gateway-1: ${LOW} rps -> ${CAP} rps (1 s jump), hold ${HOLD}s, back to ${LOW} rps`];
  for (const p of PHASES) lines.push(phaseLine(p, t.perPhase.b[p]));
  lines.push(`dropped iterations total=${droppedTotal} by scenario=${JSON.stringify(dropped)} failed rate=${f(result.failedRate, 5)}`, '');
  const out = __ENV.OUT || `/results/burst-${MIX}.json`;
  return { stdout: lines.join('\n'), [out]: JSON.stringify(result) };
}
