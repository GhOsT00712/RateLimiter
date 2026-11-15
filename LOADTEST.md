# Rate Limiter: Load Test Plan

Load tests for the services in this repo, using k6, against the numbers in the design (`rateLimiter.excalidraw`):

- 1M rate-limit checks/sec at peak, with bursts.
- Limiter latency under 5 ms at p95 and 10 ms at p99.
- Limits that hold across gateway nodes.

A single machine cannot generate 1M requests/sec. The plan measures per-node capacity and added latency locally, then extrapolates the node and Redis count for 1M.

## What each design number becomes

| Design number | What we measure locally | Pass condition |
|---|---|---|
| <5 ms p95, <10 ms p99 limiter latency | Added latency: a rate-limited route minus the same route with no rule, at the same request rate | Difference under 5 ms at p95 and 10 ms at p99 |
| 1M req/sec peak | Highest steady rate one gateway node sustains before p99 breaks or errors appear | Reported as requests/sec per node, then nodes needed = 1M / that |
| Burst | A jump from low to high rate within one second | No errors; latency back to normal within a few seconds |
| Deterministic limit | Allowed requests for one identity, spread across two gateways | Never more than `capacity + refillPerSec x duration` |
| Redis sizing (~100 bytes per key) | Redis memory with 100k and 1M distinct identities | Bytes per key measured; shard count derived for the real key count |
| PA/EL (availability first) | Redis stopped mid-test; control plane stopped mid-test | Requests keep flowing per `onFailure`; control plane outage has no effect on the request path |

## Setup

- Everything in Docker Compose: two gateway instances, demo service, control plane, Redis, MongoDB, and k6 from the `grafana/k6` image.
- CPU limits on each container, so k6 and the gateways do not steal from each other and the per-node figure means something.
- Rules created through the control plane before each run.
- A `loadtest/` folder with the k6 scripts, the compose overlay, and a results table.

## Scenarios

| # | Scenario | Shape | Answers |
|---|---|---|---|
| 1 | Baseline | Constant rate on a route with no matching rule | Gateway and backend latency without the limiter |
| 2 | Limiter overhead | Same rate, many users all under their limit; run alongside 1 | The 5 ms / 10 ms target |
| 3 | Capacity ramp | Rate ramps up until p99 breaks or errors appear | Requests/sec per node; nodes for 1M |
| 4 | Accuracy | One identity, then 1,000 identities, hammered through both gateways | Limits hold across nodes; with leasing, also how much is under-admitted |
| 5 | Burst | 10% of capacity jumping to 100% in one second, held, then back | Burst handling |
| 6 | Key cardinality | 100k, then 1M distinct identities | Redis bytes per key; keys expire after the TTL |
| 7 | Hot key | One identity sends half of all traffic | No collapse when one bucket is contended |
| 8 | Failures under load | Stop Redis; stop the control plane; update a rule mid-run | Availability behaviour; rule change applied within one poll with no errors |
| 9 | Soak | 20 to 30 minutes at half of the measured capacity | No memory growth in the gateway or Redis |

Config writes (10 per month in the design) need no load test of their own; the mid-run rule update in scenario 8 covers them.

## Order of work

1. Dockerize the three apps and add the two-gateway compose setup.
2. Write scenarios 1 to 4 and run them against the current build. Every request makes one Redis call at this point, so this is the "direct mode" baseline.
3. Build phase 6 of `PLAN.md` (leasing and failure modes), then rerun 1 to 4 to show what leasing changes, and add scenarios 5 to 9.
4. Record results and the 1M extrapolation in `loadtest/README.md`.

## Limits of this plan

- The 1M figure is an extrapolation. It assumes gateways scale linearly and says nothing about network or a real Redis Cluster under that load.
- Latency on one machine is optimistic. There is no network hop to Redis; a real deployment adds that round trip to every Redis call.
- Scenario 8's Redis test depends on phase 6. Before it, the gateway lets everything through when Redis is down, regardless of the rule's `onFailure`.
