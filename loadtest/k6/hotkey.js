// Scenario 7: hot key on ONE gateway (gateway-1). One identity sends HOT_SHARE of the traffic, the rest is
// spread over COLD_IDS identities, all under the same per-user rule. Total rate = RATE (about 70 % of the
// measured per-node capacity).
//
//   k6 run -e RATE=4200 hotkey.js
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { GW1, TREND_STATS, newRunId, putRule, deleteRule, waitForSync, f, val } from './common.js';
import { rec, tlThresholds, tlSummary, phaseLine } from './timeline.js';

const RATE = Number(__ENV.RATE || 4200);
const DURATION = Number(__ENV.DURATION || 60);
const HOT_SHARE = Number(__ENV.HOT_SHARE || 0.5);
const COLD_IDS = Number(__ENV.COLD_IDS || 100000);
const CAPACITY = Number(__ENV.CAPACITY || 1000);
const REFILL = Number(__ENV.REFILL || 500);
const LEASE_TTL = 1;

const hotOk = new Counter('hot_allowed');
const hotDenied = new Counter('hot_denied');
const hotStart = new Trend('hot_t_start_ms');
const hotEnd = new Trend('hot_t_end_ms');
const expected = http.expectedStatuses(200, 429);

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    mix: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION}s`,
      preAllocatedVUs: 400, maxVUs: 3000,
    },
  },
  thresholds: {
    ...tlThresholds(['hot', 'hot_ok', 'hot_429', 'cold'], 0, ['all']),
    dropped_iterations: ['count>=0'],
    http_req_failed: ['rate>=0'],
  },
};

export function setup() {
  const runId = newRunId();
  const ruleId = `lt-hotkey-${runId}`;
  putRule(ruleId, { match: { method: 'GET', path: '/v1/orders/hotkey/**' }, keyBy: 'userId', capacity: CAPACITY, refillPerSec: REFILL });
  waitForSync('/v1/orders/hotkey/probe', `probe-${runId}`);
  return { runId, ruleId };
}

export function teardown(d) {
  deleteRule(d.ruleId);
}

export default function (d) {
  const hot = Math.random() < HOT_SHARE;
  const id = hot ? `hot-${d.runId}` : `c-${d.runId}-${Math.floor(Math.random() * COLD_IDS)}`;
  const t0 = Date.now();
  const res = http.get(`${GW1}/v1/orders/hotkey/${Math.floor(Math.random() * 1000)}`, {
    headers: { 'X-User-Id': id },
    responseCallback: expected,
  });
  rec(hot ? 'hot' : 'cold', 0, 'all', res);
  if (hot) {
    rec(res.status === 200 ? 'hot_ok' : 'hot_429', 0, 'all', res); // latency by outcome
    hotStart.add(t0);
    hotEnd.add(Date.now());
    if (res.status === 200) hotOk.add(1);
    else if (res.status === 429) hotDenied.add(1);
  }
}

export function handleSummary(data) {
  const t = tlSummary(data, ['hot', 'hot_ok', 'hot_429', 'cold'], 0, ['all']);
  const hot = t.perPhase.hot.all;
  const cold = t.perPhase.cold.all;
  const allowed = val(data.metrics['hot_allowed'], 'count') || 0;
  const denied = val(data.metrics['hot_denied'], 'count') || 0;
  const elapsed = (val(data.metrics['hot_t_end_ms'], 'max') - val(data.metrics['hot_t_start_ms'], 'min')) / 1000;
  const ideal = CAPACITY + REFILL * elapsed;
  const bound = CAPACITY + REFILL * (elapsed + LEASE_TTL);
  const dropped = val(data.metrics['dropped_iterations'], 'count') || 0;
  const result = {
    scenario: 'hotkey', date: new Date().toISOString(), rateRps: RATE, hotShare: HOT_SHARE, coldIdentities: COLD_IDS,
    capacity: CAPACITY, refillPerSec: REFILL, durationSec: DURATION,
    hot, hotAllowedLatency: t.perPhase.hot_ok.all, hotDeniedLatency: t.perPhase.hot_429.all, cold, hotAllowed: allowed, hotDenied: denied, elapsedSec: elapsed, boundDirect: ideal, boundLeasing: bound,
    allowedOverLeasingBound: allowed / bound, underAdmissionPctVsDirectBound: (1 - allowed / ideal) * 100,
    hotLimitRespected: allowed <= bound + 1, coldDenied: cold.n429, droppedIterations: dropped,
    failedRate: val(data.metrics['http_req_failed'], 'rate'),
  };
  const lines = [
    `Hot key on gateway-1: ${RATE} rps, ${HOT_SHARE * 100}% to one identity, rest over ${COLD_IDS} identities; rule cap=${CAPACITY} refill=${REFILL}/s; ${DURATION}s`,
    phaseLine('hot identity (all)', hot),
    phaseLine('  hot, allowed', t.perPhase.hot_ok.all),
    phaseLine('  hot, denied', t.perPhase.hot_429.all),
    phaseLine('other identities', cold),
    `hot identity: allowed=${allowed} denied=${denied} elapsed=${f(elapsed, 3)}s direct bound=${f(ideal, 1)} leasing bound=${f(bound, 1)} -> ${allowed <= bound + 1 ? 'RESPECTED' : 'VIOLATED'}, ${f((allowed / bound) * 100, 2)}% of leasing bound, under-admission vs direct bound ${f(result.underAdmissionPctVsDirectBound, 2)}%`,
    `cold identities denied=${cold.n429} dropped=${dropped} failed rate=${f(result.failedRate, 5)}`,
    '',
  ];
  const out = __ENV.OUT || '/results/hotkey.json';
  return { stdout: lines.join('\n'), [out]: JSON.stringify(result, null, 2) };
}
