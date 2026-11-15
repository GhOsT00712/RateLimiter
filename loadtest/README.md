# Load tests

k6 scenarios 1 to 4 of `LOADTEST.md`, run against the current build (direct mode: one Redis Lua call per request per matching rule, no local leasing).

## How to run

Needs Docker, Maven, Java 21. From the repo root, in Git Bash:

```
loadtest/run.sh up                          # mvn package, build images, start stack (--wait)
loadtest/run.sh run overhead -e RATE=3000 -e DURATION=60      # scenarios 1+2
loadtest/run.sh run ramp -e START=5500 -e STEP=500 -e STEPS=8 -e STEP_SEC=30   # scenario 3
loadtest/run.sh run accuracy                # scenario 4
loadtest/run.sh stats                       # docker stats snapshot
loadtest/run.sh down                        # docker compose down -v
```

Extra `-e OUT=/results/<name>.json` chooses the JSON file name. Each run prints a summary, saves it to `loadtest/results/<scenario>-<timestamp>.txt`, and `handleSummary` writes a JSON file next to it. `loadtest/sample-stats.sh <file>` samples `docker stats` every 5 s while a scenario runs.

Each script creates its rules through the control plane in `setup()` (unique rule ids and identities per run), waits until both gateways return `X-RateLimit-Limit` for a probe identity, and deletes the rules in `teardown()`. All load is open-model (`constant-arrival-rate`).

The stack uses compose project `rl-loadtest` and its own network. Redis and Mongo are not published. Gateways are on host ports 18080 and 18090, the control plane on 18081.

## Stack and CPU limits

| Container | CPUs | Notes |
|---|---|---|
| gateway-1, gateway-2 | 2 each | `-Xms512m -Xmx512m`, `RL_SYNC_INTERVAL=2s`, `RL_SYNC_JITTER=0s` |
| demo-service | 4 | `-Xms512m -Xmx512m` |
| redis (7.4-alpine) | 2 | |
| control-plane | 1 | `-Xms512m -Xmx512m` |
| mongo (8.0) | 1 | |
| k6 | 8 | |

These are the suggested starting values; no adjustment was needed (see the ramp section for the evidence). Host: Windows 11, Docker Desktop with 32 CPUs / ~30 GB.

## Results (2026-11-15)

Latency is `http_req_duration` as seen by k6 in ms. Raw data is in `loadtest/results/`.

### Scenarios 1 + 2: baseline and limiter overhead

Baseline (`/v1/orders/baseline/**`, no rule) and limited (`/v1/orders/limited/**`, `userId` rule, capacity 1,000,000, 50,000 distinct users, zero 429s) each at 3,000 req/s (6,000 req/s total), split evenly over both gateways, 15 s warm-up then 60 s measured. At this rate each gateway handles 3,000 req/s, about half of its single-node limit from the ramp.

| Run | Baseline p50 / p95 / p99 | Limited p50 / p95 / p99 | Added p95 | Added p99 | Target (5 / 10 ms) |
|---|---|---|---|---|---|
| 1 | 0.64 / 0.83 / 1.01 | 0.92 / 1.16 / 1.40 | 0.32 | 0.38 | PASS |
| 2 | 0.66 / 0.85 / 1.02 | 0.95 / 1.18 / 1.40 | 0.33 | 0.37 | PASS |
| 3 | 0.66 / 0.85 / 1.03 | 0.96 / 1.18 / 1.39 | 0.33 | 0.36 | PASS |

The result is stable across three runs: the limiter adds about 0.3 ms at p50, p95 and p99. There were no 429s and no failed requests. Runs 1 and 2 reported 26 and 4 dropped iterations (of about 450,000 each, counting the warm-up phase), so those two runs are not "clean" by the script's own strict check; run 3 had none. The latency numbers are not materially different between the runs. This is single-machine latency with no network hop to Redis (see caveats).

An earlier smoke run at 1,000 req/s per scenario gave added p95 0.37 ms and p99 0.46 ms (not kept in the results folder).

### Scenario 3: capacity ramp (one gateway)

Limited path, `gateway-1` only, 30 s steps, healthy means p99 <= 50 ms, no failed requests, no dropped iterations, achieved >= 98 % of target. Two ramps were run on the same warm stack (`ramp-3000-6500.json`, `ramp-5500-9000.json`):

| Target rps | p50 | p95 | p99 | Healthy? |
|---|---|---|---|---|
| 3000 | 0.90 | 1.16 | 4.28 | yes |
| 4000 | 0.93 | 1.21 | 1.98 | yes |
| 5000 | 1.02 | 10.44 | 21.65 | yes |
| 5500 | 1.15 | 22.86 | 36.51 | yes (second ramp) |
| 6000 | 1.27 / 1.36 | 31.57 / 31.31 | 40.86 / 43.81 | yes (both ramps) |
| 6500 | 2.26 / 2.66 | 40.40 / 41.19 | 49.64 / 50.63 | borderline: yes in ramp 1, no in ramp 2 |
| 7000 | 9.35 | 51.02 | 63.14 | no (p99) |
| 8000 | 27.88 | 74.12 | 92.42 | no |
| 9000 | 69.93 | 156.90 | 193.90 | no (also achieved 8,762 of 9,000) |

(Two values in a cell are ramp 1 / ramp 2.)

**Per-node capacity: 6,000 req/s** (highest step healthy in both ramps; 6,500 sits on the 50 ms line). Failed requests and dropped iterations were zero at every step; the ceiling was hit on p99, then on achieved rate. A cold first exploratory ramp (fresh JVMs, steps of 15 s, `ramp-explore.json`) failed already at 6,000 (p99 54 ms) and saturated at roughly 9,000 achieved; the JIT-warm results above are the ones reported, but expect a node that has just started to do worse for the first minute.

**What saturated first: the gateway CPU.** `docker stats` during the ramps (`ramp-*-stats.txt`, 5 s samples): gateway-1 median 197 to 201 % of its 2-CPU limit (max 208 %). The other containers had plenty of headroom: Redis median about 27 % of one core (max 44 %), demo-service median 63 to 75 % of one core (limit 4 cores, max 87 %), k6 median 114 to 153 % of one core (limit 8 cores, max 244 %), gateway-2 and control plane idle. So the number is the gateway's, not k6's, Redis's or the demo service's. The p95 growth from 5,000 req/s up (while p50 stays near 1 ms) is typical of CPU queueing/GC on a CPU-capped JVM; I did not profile it.

### Scenario 4: accuracy across two gateways

Two runs, identical results.

| Check | Setup | Measured | Bound | Result |
|---|---|---|---|---|
| 4a one identity | capacity 100, refill 10/s, 2,000 req/s alternating gateways for 30 s | allowed 399 (59,602 denied), elapsed 29.998 s (run 2: 399, 29.999 s) | 100 + 10 x elapsed = 399.98 (run 2: 399.99) | PASS, at 99.75 % of the bound |
| 4b 1,000 identities | capacity 5, refill 0.001/s, 3,000 req/s for 30 s, each identity alternating gateways | total allowed 5,000 (85,001 denied); max for any identity 5; 0 identities over their bound; 1,000 of 1,000 reached capacity | 5,000 whole tokens (5,030 counting the fractional refill) | PASS |

Elapsed time for 4a is measured in k6 from the first request start to the last response end, so it is an upper bound on the bucket's lifetime. No dropped iterations. Direct mode admits essentially the exact limit; there is no under-admission to measure until leasing exists.

## Extrapolation to 1M req/s (an estimate, not a measurement)

- Per-node capacity measured here: 6,000 req/s on 2 CPUs.
- Nodes for 1M req/s = 1,000,000 / 6,000 = 167 gateway nodes (about 334 CPUs), assuming linear scaling.
- Redis calls: this build makes one Lua call per request per matching rule. With one matching rule per request that is about 1,000,000 Lua calls/s. With N matching rules per request it is N times that.
- Crude Redis sizing from the same data: Redis used a median of about 0.27 core while serving roughly 6,000 to 7,000 calls/s, i.e. on the order of 40 microseconds of CPU per call or about 22,000 calls/s per core. At that figure 1M calls/s needs roughly 45 single-threaded Redis shards. This mixes container accounting and one measurement point, so treat it as an order of magnitude only.

Leasing (phase 6) is meant to cut the Redis call rate far below one per request; that is not measured here.

## Caveats

- Single machine: gateways, Redis, demo service and k6 share one host and one Docker VM. CPU limits isolate them only partly (memory bandwidth, caches and the VM scheduler are shared).
- No network hop to Redis, so real latency will be higher by the round trip of every Redis call.
- k6 runs in a container on the same host. It used well under its 8 CPUs, but it is still a source of noise.
- Direct mode only: every request makes a Redis call. Results will change with leasing.
- The demo backend returns instantly; a real backend adds its own latency and connection behaviour.
- Latency percentiles come from k6's `http_req_duration` and include the demo-service hop for both baseline and limited requests; the "added" figure is the difference between the two, which cancels it.
- Results depend on JIT warm-up (see the cold ramp note) and on the host's other load; the ceiling step moved by one step (6,000 to 6,500) between two ramps.
- Not covered here: scenarios 5 to 9 (burst, key cardinality, hot key, failures, soak).


---

# Round 2 (2026-11-15): with token leasing

Stack as in round 1, plus `RL_NODE_COUNT=2`, `RL_LEASE_ENABLED=true` and `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics` (read-only JVM metrics) on both gateways. Jars built with `mvn -B -Dmaven.test.skip=true package`. CPU limits unchanged (gateways 2 CPUs each, Redis 2, k6 8).

**Status: round 2 was cut short on request. Only scenarios 1+2 were run. Scenarios 3 to 9 were NOT run, and no number for them exists.** The k6 scripts and `run.sh` entries for all of them were written but only the overhead scenario was exercised; the others are untested code.

## How to rerun (round 2)

```
loadtest/run.sh up
loadtest/run.sh r2 overhead-cold | overhead-hot | ramp-cold | ramp-hot | accuracy
loadtest/run.sh r2 burst-cold|burst-hot   (CAP=<per-node rps>)   cardinality
loadtest/run.sh r2 hotkey (RATE=<rps>)    fail-a | fail-b | fail-c
loadtest/run.sh r2 soak (PER_NODE=<rps per gateway>, DURATION=1200)
```
`TAG=<suffix>` renames the result files (`r2-<scenario>-<suffix>.*`). Existing result files are never overwritten. Samplers (`sample-stats.sh`) are bounded by a max duration, exit when the launching shell is gone, and `run.sh` kills them and removes its k6 containers on exit. Fault injection (Redis pause, control-plane stop) is timed from the k6 `T0` log line, with VM/host clock skew measured.

## Scenarios 1 + 2: baseline vs limiter overhead, 3,000 req/s per scenario

Same shape as round 1 (baseline and limited concurrently, 15 s warm-up + 60 s, both gateways). "Cold keys" = 50,000 distinct users (low-traffic keys, one Redis call per request). "Hot keys" = 50 users on one rule (cap 1,000,000, refill 1000/s, nothing denied; leasing active).

| Variant / run | Baseline p50 / p95 / p99 | Limited p50 / p95 / p99 | Added p95 | Added p99 | Target (5 / 10 ms) | Notes |
|---|---|---|---|---|---|---|
| Cold, run 1 | 0.68 / 1.10 / 34.68 | 1.03 / 1.55 / 35.86 | 0.45 | 1.18 | PASS | first run on freshly started JVMs: 7,946 dropped iterations, p99 inflated for both |
| Cold, run 2 | 0.68 / 0.91 / 1.09 | 1.01 / 1.27 / 1.49 | 0.36 | 0.39 | PASS | clean |
| Cold, run 3 | 0.67 / 0.87 / 1.04 | 0.99 / 1.22 / 1.41 | 0.35 | 0.37 | PASS | clean |
| Hot, run 1 | 0.66 / 0.87 / 1.04 | 0.68 / 1.01 / 1.25 | 0.13 | 0.20 | PASS | clean |
| Hot, run 2 | 0.66 / 0.86 / 1.02 | 0.66 / 0.98 / 1.21 | 0.12 | 0.19 | PASS | clean |

(The hot-key third run was started and then aborted on request; its files were deleted.) No 429s, no failed requests, no dropped iterations except cold run 1. Zero `Redis unavailable` / `Redis recovered` / `LEAK:` / `OutOfMemoryError` lines and zero restarts in every one of these runs (`r2-overhead-*-logs.txt`).

### Round 1 vs round 2 (scenarios 1 + 2 only)

| | Round 1 (direct) added p95 / p99 | Round 2 cold keys | Round 2 hot keys |
|---|---|---|---|
| Added latency | 0.32-0.33 / 0.36-0.38 ms | 0.35-0.36 / 0.37-0.39 ms (warm runs) | 0.12-0.13 / 0.19-0.20 ms |

Cold keys are unchanged, as expected (they behave like direct mode). Hot keys add about 0.12 ms at p95 and 0.2 ms at p99, about a third of the cold figure, which is consistent with most requests being served from the local lease. I did not measure Redis calls per second for these runs, so the call reduction itself is not quantified. Scenarios 3 and 4 (capacity, accuracy) have no round 2 numbers.

## Not run (no data)

3 capacity ramp (cold and hot), 4 accuracy incl. 4c, 5 burst, 6 key cardinality, 7 hot key, 8 failures under load (Redis pause, control plane stop, rule change), 9 soak (never started). Consequently there is no updated 1M req/s extrapolation: the round 1 extrapolation above (167 nodes, ~1M Redis calls/s in direct mode) still stands as the only estimate, and the effect of leasing on it is unmeasured.

## Observations and caveats

- The first overhead run after a fresh build had 7,946 dropped iterations and p99 around 35 ms (cold JVMs); runs after it were clean. Round 1 already noted cold-start sensitivity.
- No bugs, breaker flapping, leaks or restarts were observed, but only the overhead scenario ran, so that says nothing about the failure paths or long-term behaviour.
- `sample-stats.sh` was rewritten (bounded, parent-aware, refuses to overwrite); the round 1 usage `sample-stats.sh <file> [interval]` still works.
- Same single-machine caveats as round 1: no network hop to Redis, k6 on the same host.
