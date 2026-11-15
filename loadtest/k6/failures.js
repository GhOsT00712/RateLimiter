// Scenario 8: failures under load. Steady load on BOTH gateways over three rules that differ only in onFailure.
// The fault itself is injected from the host by loadtest/run.sh (it waits for the "T0 <epoch ms>" line this
// script prints when the load starts), except MODE=c where the rule change is made by this script.
//
//   MODE=a  Redis paused (docker pause) at T_FAULT for FAULT_SEC
//   MODE=b  control plane stopped at T_FAULT for FAULT_SEC
//   MODE=c  rule 'open' capacity/refill doubled through the control plane at T_FAULT (by k6)
//
// groups: open | closed | fallback (rule kind), phases: before | during | after (nominal; warm-up excluded)
import http from 'k6/http';
import exec from 'k6/execution';
import { GW1, GW2, TREND_STATS, newRunId, putRule, deleteRule, waitForSync, val } from './common.js';
import { rec, tlThresholds, tlSummary, phaseLine } from './timeline.js';

const MODE = __ENV.MODE || 'a';
const RATE = Number(__ENV.RATE || 1800); // total over both gateways
const WARM = Number(__ENV.WARM || 20);
const T_FAULT = Number(__ENV.T_FAULT || 40);
const FAULT_SEC = Number(__ENV.FAULT_SEC || (MODE === 'b' ? 20 : 15));
const TOTAL = Number(__ENV.TOTAL || (MODE === 'c' ? 80 : 100));
const IDS = 20; // identities per rule
const GROUPS = ['open', 'closed', 'fallback'];
const PHASES = ['before', 'during', 'after'];
const PATHS = { open: 'fo', closed: 'fc', fallback: 'fl' };
const expected = http.expectedStatuses(200, 429, 503);

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    load: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${TOTAL}s`,
      preAllocatedVUs: 300, maxVUs: 3000, exec: 'load',
    },
    ...(MODE === 'c' ? { change: { executor: 'per-vu-iterations', vus: 1, iterations: 1, startTime: `${T_FAULT}s`, exec: 'change' } } : {}),
  },
  thresholds: {
    ...tlThresholds(GROUPS, TOTAL + 3, PHASES, ['open']),
    dropped_iterations: ['count>=0'],
    http_req_failed: ['rate>=0'],
  },
};

const BODY = (kind, path) => ({
  match: { method: 'GET', path: `/v1/orders/${path}/**` },
  keyBy: 'userId',
  capacity: 100,
  refillPerSec: kind === 'fallback' ? 25 : 60, // load is 30 req/s per identity: only the fallback rule is over its limit
  onFailure: kind === 'fallback' ? 'local_fallback' : kind,
});

export function setup() {
  const runId = newRunId();
  const rules = {};
  for (const g of GROUPS) {
    rules[g] = `lt-fail-${g}-${runId}`;
    putRule(rules[g], BODY(g, PATHS[g]));
  }
  for (const g of GROUPS) waitForSync(`/v1/orders/${PATHS[g]}/probe`, `probe-${g}-${runId}`);
  const t0 = Date.now();
  console.log(`T0 ${t0}`);
  return { runId, rules, t0 };
}

export function teardown(d) {
  for (const g of GROUPS) deleteRule(d.rules[g]);
}

function phaseOf(sec) {
  if (sec < WARM) return 'warmup';
  if (sec < T_FAULT) return 'before';
  if (sec < T_FAULT + FAULT_SEC) return 'during';
  return 'after';
}

export function load(d) {
  const i = exec.scenario.iterationInTest;
  const g = GROUPS[i % 3];
  const gwIdx = Math.floor(i / 3) % 2;
  const id = Math.floor(i / 6) % IDS;
  const res = http.get(`${gwIdx === 0 ? GW1 : GW2}/v1/orders/${PATHS[g]}/${i % 1000}`, {
    headers: { 'X-User-Id': `f-${d.runId}-${g}-${id}` },
    responseCallback: expected,
  });
  const sec = Math.floor((Date.now() - d.t0) / 1000);
  const ph = phaseOf(sec);
  rec(g, sec, ph === 'warmup' ? 'before_warmup_unused' : ph, res, g === 'open' ? (gwIdx === 0 ? 'gw1' : 'gw2') : undefined);
}

export function change(d) {
  const b = BODY('open', PATHS.open);
  b.capacity = 200;
  b.refillPerSec = 120;
  console.log(`CHANGE_START ${Date.now()}`);
  putRule(d.rules.open, b);
  console.log(`CHANGE_DONE ${Date.now()}`);
}

export function handleSummary(data) {
  const t = tlSummary(data, GROUPS, TOTAL + 3, PHASES, ['open']);
  const result = {
    scenario: 'failures', mode: MODE, date: new Date().toISOString(), rateRps: RATE, warmSec: WARM, tFaultSec: T_FAULT,
    faultSec: FAULT_SEC, totalSec: TOTAL, phases: t.perPhase, perSec: t.perSec, limitSeen: t.limits,
    droppedIterations: val(data.metrics['dropped_iterations'], 'count') || 0,
  };
  const lines = [`Failures mode ${MODE}: ${RATE} rps over both gateways, fault at ${T_FAULT}s for ${FAULT_SEC}s (nominal), total ${TOTAL}s`];
  for (const g of GROUPS) for (const p of PHASES) lines.push(phaseLine(`${g}/${p}`, t.perPhase[g][p]));
  lines.push(`dropped iterations=${result.droppedIterations}`, '');
  const out = __ENV.OUT || `/results/failures-${MODE}.json`;
  return { stdout: lines.join('\n'), [out]: JSON.stringify(result) };
}
