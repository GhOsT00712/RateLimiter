// Shared helpers for the k6 scenarios.
import http from 'k6/http';
import { sleep } from 'k6';

export const GW1 = __ENV.GW1 || 'http://gateway-1:8080';
export const GW2 = __ENV.GW2 || 'http://gateway-2:8080';
export const CP = __ENV.CP || 'http://control-plane:8081';
export const SERVICE = 'orders';
const TOKEN = __ENV.SERVICE_TOKEN || 'dev-orders-token';

export const TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

export function newRunId() {
  return Date.now().toString(36) + Math.floor(Math.random() * 1296).toString(36);
}

function cpHeaders() {
  return { 'Content-Type': 'application/json', 'X-Service-Token': TOKEN };
}

export function putRule(ruleId, rule) {
  const url = `${CP}/v1/services/${SERVICE}/rules/${ruleId}`;
  let res = http.put(url, JSON.stringify(rule), { headers: cpHeaders() });
  if (res.status >= 300) {
    throw new Error(`PUT ${ruleId} failed: ${res.status} ${res.body}`);
  }
}

export function deleteRule(ruleId) {
  const res = http.del(`${CP}/v1/services/${SERVICE}/rules/${ruleId}`, null, { headers: cpHeaders() });
  if (res.status >= 300 && res.status !== 404) {
    console.warn(`DELETE ${ruleId} failed: ${res.status}`);
  }
}

function header(res, name) {
  const lname = name.toLowerCase();
  for (const k of Object.keys(res.headers)) {
    if (k.toLowerCase() === lname) return res.headers[k];
  }
  return undefined;
}
export { header };

// Poll each gateway until a request on probePath carries X-RateLimit-Limit,
// i.e. the gateway has loaded the rule. Probes use their own identity so they
// never touch the buckets the test measures.
export function waitForSync(probePath, probeUser, timeoutSec = 60) {
  const deadline = Date.now() + timeoutSec * 1000;
  for (const gw of [GW1, GW2]) {
    let ok = false;
    while (Date.now() < deadline) {
      const res = http.get(`${gw}${probePath}`, {
        headers: { 'X-User-Id': probeUser },
        tags: { phase: 'probe' },
        responseCallback: http.expectedStatuses(200, 429),
      });
      if ((res.status === 200 || res.status === 429) && header(res, 'X-RateLimit-Limit') !== undefined) {
        ok = true;
        break;
      }
      sleep(0.5);
    }
    if (!ok) throw new Error(`gateway ${gw} did not pick up the rule for ${probePath} within ${timeoutSec}s`);
  }
}

function val(metric, stat) {
  if (!metric) return undefined;
  const v = metric.values || metric;
  return v[stat];
}
export { val };

export function lat(data, name) {
  const m = data.metrics[name];
  return {
    count: val(data.metrics[name.replace('http_req_duration', 'http_reqs')], 'count'),
    avg: val(m, 'avg'),
    p50: val(m, 'med'),
    p95: val(m, 'p(95)'),
    p99: val(m, 'p(99)'),
    max: val(m, 'max'),
  };
}

export const f = (x, d = 2) => (x === undefined || x === null || Number.isNaN(x) ? 'n/a' : Number(x).toFixed(d));
