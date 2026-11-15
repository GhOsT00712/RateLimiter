// Round 2 helper: per-second and per-phase status/latency aggregation, so a scenario can be
// analysed over time without per-request dumps.
//
// k6 only reports sub-metrics that a threshold names, so tlThresholds() lists every
// (group, second) and (group, phase) the script may emit. Tag values use '_' (no '|' or ':').
import { Counter, Trend } from 'k6/metrics';
import { val } from './common.js';

const CODES = ['200', '429', '503', 'oth'];
const secDur = new Trend('tl_dur', true);
const phDur = new Trend('ph_dur', true);
const lim = new Trend('tl_lim');
const secCnt = {};
const phCnt = {};
for (const c of CODES) {
  secCnt[c] = new Counter(`tl_${c}`);
  phCnt[c] = new Counter(`ph_${c}`);
}

const code = (status) => (status === 200 || status === 429 || status === 503 ? String(status) : 'oth');

// group: traffic class (e.g. rule kind), sec: whole seconds since the run started, ph: phase name.
export function rec(group, sec, ph, res, gwLabel) {
  const c = code(res.status);
  const ks = { k: `${group}_${sec}` };
  const kp = { k: `${group}_${ph}` };
  secDur.add(res.timings.duration, ks);
  secCnt[c].add(1, ks);
  phDur.add(res.timings.duration, kp);
  phCnt[c].add(1, kp);
  if (gwLabel) {
    for (const k of Object.keys(res.headers)) {
      if (k.toLowerCase() === 'x-ratelimit-limit') {
        lim.add(Number(res.headers[k]), { k: `${group}_${gwLabel}_${sec}` });
      }
    }
  }
}

export function tlThresholds(groups, maxSec, phases, limGroups) {
  const th = {};
  for (const g of groups) {
    for (let s = 0; s <= maxSec; s++) {
      th[`tl_dur{k:${g}_${s}}`] = ['max>=0'];
      for (const c of CODES) th[`tl_${c}{k:${g}_${s}}`] = ['count>=0'];
    }
    for (const p of phases) {
      th[`ph_dur{k:${g}_${p}}`] = ['max>=0'];
      for (const c of CODES) th[`ph_${c}{k:${g}_${p}}`] = ['count>=0'];
    }
  }
  for (const g of limGroups || []) {
    for (const gw of ['gw1', 'gw2']) {
      for (let s = 0; s <= maxSec; s++) th[`tl_lim{k:${g}_${gw}_${s}}`] = ['max>=0'];
    }
  }
  return th;
}

const r3 = (x) => (x === undefined || x === null ? null : Math.round(x * 1000) / 1000);

function row(data, durName, cntPrefix, key) {
  const d = data.metrics[`${durName}{k:${key}}`];
  const out = {};
  let n = 0;
  for (const c of CODES) {
    const v = val(data.metrics[`${cntPrefix}${c}{k:${key}}`], 'count') || 0;
    out[`n${c}`] = v;
    n += v;
  }
  out.n = n;
  if (n > 0) {
    out.p50 = r3(val(d, 'med'));
    out.p95 = r3(val(d, 'p(95)'));
    out.p99 = r3(val(d, 'p(99)'));
    out.max = r3(val(d, 'max'));
  }
  return out;
}

export function tlSummary(data, groups, maxSec, phases, limGroups) {
  const perSec = {};
  const perPhase = {};
  const limits = {};
  for (const g of groups) {
    perSec[g] = [];
    for (let s = 0; s <= maxSec; s++) {
      const r = row(data, 'tl_dur', 'tl_', `${g}_${s}`);
      if (r.n > 0) perSec[g].push({ s, ...r });
    }
    perPhase[g] = {};
    for (const p of phases) perPhase[g][p] = row(data, 'ph_dur', 'ph_', `${g}_${p}`);
  }
  for (const g of limGroups || []) {
    limits[g] = { gw1: [], gw2: [] };
    for (const gw of ['gw1', 'gw2']) {
      for (let s = 0; s <= maxSec; s++) {
        const m = data.metrics[`tl_lim{k:${g}_${gw}_${s}}`];
        if (m && val(m, 'max') !== undefined && val(m, 'max') !== null) {
          limits[g][gw].push({ s, min: val(m, 'min'), max: val(m, 'max') });
        }
      }
    }
  }
  return { perSec, perPhase, limits };
}

export function phaseLine(name, r) {
  const f = (x) => (x === undefined || x === null ? 'n/a' : Number(x).toFixed(2));
  return `${name.padEnd(22)} n=${String(r.n).padEnd(8)} 200=${r.n200} 429=${r.n429} 503=${r.n503} other=${r.noth} p50=${f(r.p50)} p95=${f(r.p95)} p99=${f(r.p99)} max=${f(r.max)} ms`;
}
