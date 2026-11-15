# Rate Limiter

A distributed rate limiter built as an API gateway filter. Rules are managed through a control plane, enforced by gateway nodes with a Redis token bucket, and served mostly from memory through token leasing.

- Design: `rateLimiter.excalidraw`
- Implementation plan: `PLAN.md`
- Load test plan: `LOADTEST.md`; full results: `loadtest/README.md`

## How it works

```
client ──▶ rl-gateway (:8080) ──▶ demo-service (:8082)
              │   ▲
   lease tokens   poll rule snapshot (ETag)
              ▼   │
           Redis  rl-control-plane (:8081) ──▶ MongoDB
```

| Module | Role |
|---|---|
| `rl-control-plane` | Stores rules in MongoDB. `PUT`/`GET`/`DELETE` rules, plus a snapshot endpoint with an ETag for gateways. |
| `rl-gateway` | Spring Cloud Gateway. A global filter checks each request against the matching rules and returns `429` when a limit is hit. |
| `demo-service` | A trivial backend behind the gateway. |

Key behaviours:

- **Token bucket in Redis.** One Lua script per check: lazy refill, one hash per bucket (`rl:<serviceId>:<ruleId>:<identity>`), TTL equal to the time to refill fully.
- **Leasing.** A gateway node leases a batch of tokens and serves requests from memory. The batch starts at 1 and doubles while a key stays busy, up to 10% of capacity. Quiet keys and small limits behave like one Redis call per request.
- **Config sync.** Gateways poll the control plane's snapshot, swap rules atomically, and keep a last-known-good copy on disk, so a gateway starts and runs with the control plane down.
- **Redis failure.** Each rule chooses `onFailure`: `open` (allow), `closed` (reject with `503`), or `local_fallback` (in-memory bucket of `capacity / nodeCount`).

## Running it

Requirements: Java 21, Maven, Docker.

### Tests

```
mvn verify
```

Runs all 64 tests. Docker must be running, because the tests start their own Redis and MongoDB containers.

### Run the services locally

1. Start Redis and MongoDB, and build the jars:

   ```
   docker compose up -d --wait
   mvn -DskipTests package
   ```

2. Start the three apps, each in its own terminal:

   ```
   java -jar rl-control-plane/target/rl-control-plane-0.1.0-SNAPSHOT.jar
   java -jar demo-service/target/demo-service-0.1.0-SNAPSHOT.jar
   java -jar rl-gateway/target/rl-gateway-0.1.0-SNAPSHOT.jar --rl.sync.interval=2s --rl.sync.jitter=0s
   ```

   The two gateway flags make it poll every 2 seconds. The default is 30 seconds plus up to 30 seconds of jitter.

3. Create a rule through the control plane (3 requests burst per user, one new token every 10 seconds). PowerShell:

   ```powershell
   $rule = '{"match":{"method":"GET","path":"/v1/orders/**"},"keyBy":"userId","capacity":3,"refillPerSec":0.1}'
   Invoke-RestMethod -Method Put -Uri http://localhost:8081/v1/services/orders/rules/per-user `
     -ContentType 'application/json' -Headers @{ 'X-Service-Token' = 'dev-orders-token' } -Body $rule
   ```

   bash:

   ```bash
   curl -X PUT http://localhost:8081/v1/services/orders/rules/per-user \
     -H 'Content-Type: application/json' -H 'X-Service-Token: dev-orders-token' \
     -d '{"match":{"method":"GET","path":"/v1/orders/**"},"keyBy":"userId","capacity":3,"refillPerSec":0.1}'
   ```

4. Send requests through the gateway a few seconds later:

   ```powershell
   1..5 | ForEach-Object { curl.exe -s -o NUL -w "%{http_code}`n" -H "X-User-Id: alice" http://localhost:8080/v1/orders/1 }
   ```

   Expected: `200 200 200 429 429`. Add `-i` to one request to see `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, and `Retry-After` on a `429`.

Things to try:

- A different `X-User-Id` gets its own bucket.
- `docker exec ratelimiter-redis-1 redis-cli hgetall rl:orders:per-user:alice` shows the bucket's `tokens` and `ts`.
- Change the rule's `capacity` with another `PUT`; the gateway applies it on its next poll.
- Stop the control plane and restart the gateway; the rule still applies from `data/rl-gateway/rules-snapshot.json`.

Cleanup: stop the apps with Ctrl+C, then `docker compose down`.

### Rule fields

| Field | Meaning |
|---|---|
| `match.method`, `match.path` | Which requests the rule covers. `*` matches anything; paths use Spring path patterns such as `/v1/orders/**`. |
| `keyBy` | `userId` (`X-User-Id` header), `apiKey` (`X-Api-Key` header), `ip` (connection address), or `global`. A missing header falls back to an `anon:<ip>` bucket. |
| `capacity` | Burst size. |
| `refillPerSec` | Sustained rate. |
| `onFailure` | `local_fallback` (default), `open`, or `closed`. |
| `enabled` | Defaults to `true`. |

The route id in the gateway's `application.yml` is the rule's `serviceId`. Writes need the `X-Service-Token` configured for that service under `rl.service-tokens` in the control plane; `dev-orders-token` is a local stand-in, not a real credential.

### Gateway settings

| Property | Default | Meaning |
|---|---|---|
| `rl.node-count` | `1` | Number of gateway nodes; stands in for service discovery. |
| `rl.lease.enabled` | `true` | Turn leasing off to make one Redis call per request. |
| `rl.lease.batch-fraction` | `0.1` | Largest lease as a fraction of capacity. |
| `rl.lease.ttl` | `1s` | How long leased tokens stay usable. |
| `rl.redis.timeout` | `100ms` | Redis call timeout before `onFailure` applies. |
| `rl.sync.control-plane-url` | `http://localhost:8081` | Where to poll rules from. |
| `rl.sync.interval`, `rl.sync.jitter` | `30s`, `30s` | Poll interval and added random delay. |
| `rl.sync.snapshot-file` | `./data/rl-gateway/rules-snapshot.json` | Last-known-good rules. |

### Load tests

The load tests run in their own Docker Compose project (`rl-loadtest`) with two gateways and k6:

```bash
bash loadtest/run.sh up              # build images and start the stack
bash loadtest/run.sh run overhead    # also: ramp, accuracy
bash loadtest/run.sh r2 overhead-hot # round 2 scenarios, by name
bash loadtest/run.sh down
```

See `loadtest/README.md` for every scenario name and the raw results.

## Load test results against the design

Measured on 15 November 2026 with k6, on one 32-CPU machine in Docker. Gateways were capped at 2 CPUs each.

| Design requirement | Measured | Status |
|---|---|---|
| Limiter latency under 5 ms at p95 | Added 0.32–0.33 ms with one Redis call per request; 0.12–0.13 ms for busy keys with leasing | Met locally |
| Limiter latency under 10 ms at p99 | Added 0.36–0.38 ms with one Redis call per request; 0.19–0.20 ms for busy keys with leasing | Met locally |
| 1M checks/sec at peak | One gateway node sustains about 6,000 req/s before p99 passes 50 ms, limited by its own CPU. That implies about 167 nodes of this size. | Estimate only; not demonstrated |
| Deterministic limit across nodes | One identity through two gateways: 399 allowed against a bound of 399.99. 1,000 identities: 5,000 allowed of 5,000, none over its limit. | Met without leasing; not load-tested with leasing |
| Burst handling | Token bucket burst is covered by tests. | Burst load scenario not run |
| Availability over consistency when Redis is unreachable | `onFailure` modes are covered by tests, and a manual Redis pause with `open` allowed every request and recovered afterwards. | Works; not measured under load |
| Eventually consistent config propagation | A rule change reaches the gateway within one poll; the gateway runs from its disk snapshot with the control plane down. | Verified by tests and a manual run |
| Redis sizing, about 100 bytes per key | Not measured. | Key cardinality scenario not run |
| 10 config writes per month | No load test needed. | Not applicable |

How "added latency" was measured: two request streams ran side by side at 3,000 req/s each for 60 seconds, one on a path with no rule and one on a rate-limited path where every request was allowed. The figure is the difference between them.

What leasing changes:

- Busy keys cost about a third of the latency of a Redis call per request.
- In a manual check, 16,000 requests for one user made 1,493 Redis calls.
- With leasing, a key is admitted at most `capacity + refillPerSec × (L + 1 s)` times in any window of length `L`, across all nodes. Leftover tokens of an expired lease are dropped, so it can under-admit.

What these numbers do not show:

- **No network hop to Redis.** Everything ran on one machine. In one availability zone, expect roughly 0.5 ms more per Redis call at the median and 1 to 2 ms at p99; that is a rule of thumb, not a measurement.
- **The 1M figure assumes linear scaling** and comes from the mode without leasing. Per-node capacity with leasing has not been measured.
- **Scenarios not run with leasing:** capacity ramp, accuracy, burst, key cardinality, hot key, failures under load, and soak. Their k6 scripts exist in `loadtest/k6/` but have never been executed.

## Open design decisions

- Accuracy: how much overshoot is acceptable.
- Capacity: active keys × bytes per key, giving Redis memory and shard count.
- Redis failover: whether losing recent counters is acceptable.
- Multi-region: limits per region or global.
- IP identity: NAT and `X-Forwarded-For` spoofing.
