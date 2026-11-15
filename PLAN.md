# Rate Limiter: Implementation Plan

Implements the design in `rateLimiter.excalidraw`.

Stack: Java 21, Spring Boot, Maven, Docker for all infrastructure.

## Layout

Multi-module Maven project in this folder:

- `rl-control-plane`: Spring Boot + MongoDB. Owns the rules.
- `rl-gateway`: Spring Cloud Gateway (reactive) + Redis. The limiter is a global filter inside it.
- `demo-service`: a trivial backend to put behind the gateway.
- `docker-compose.yml`: Redis and MongoDB first, the three apps later.

## Phases

Each phase runs and is testable before the next starts.

### 1. Scaffold

- Parent POM and the three modules.
- Compose file with Redis and MongoDB.

Done when: `mvn verify` passes and both containers are healthy.

### 2. Control plane

- `RLConfig` document: `serviceId`, `ruleId`, `match {method, path}`, `keyBy`, `capacity`, `refillPerSec`, `onFailure`, `enabled`, `version`, `updatedAt`. Primary key `serviceId + ruleId`.
- `PUT /v1/services/{serviceId}/rules/{ruleId}`: upsert. `If-None-Match: *` on create, `If-Match: <version>` on update, `412` on mismatch, `400` on invalid config, `403` if the caller does not own the service.
- `GET /v1/services/{serviceId}/rules` and `DELETE /v1/services/{serviceId}/rules/{ruleId}`.
- `GET /v1/rules/snapshot`: all rules plus an `ETag`; `304` with `If-None-Match` when unchanged.

Done when: tests cover create, stale-version `412`, and `304` on an unchanged snapshot.

### 3. Limiter core

- Lua token-bucket script: lazy refill, Redis `TIME`, one hash per bucket (`rl:<serviceId>:<ruleId>:<identity>` with `tokens` and `ts`), TTL of `capacity / refillPerSec`.
- Redis client wrapper, direct mode only (one token per call).

Done when: tests against a real Redis show burst, refill over time, TTL, and retry-after.

### 4. Gateway filter

- Route to `demo-service`.
- Match rules by method and path, extract identity, check every matching rule (most restrictive first).
- Return `429` with `Retry-After`, or pass through with `X-RateLimit-Limit/Remaining/Reset`.

Done when: a curl loop gets 200s up to the limit, then 429s.

### 5. Config sync

- Poll the snapshot every 30-60s with jitter and `If-None-Match`.
- Atomic in-memory swap of the rule table.
- Last-known-good snapshot on disk (write temp file, then rename).
- Exponential backoff on failure.

Done when: changing a rule takes effect within one poll, and the gateway starts with the control plane stopped.

### 6. Leasing and failure modes

- Local token batches leased from Redis, at most 10% of capacity.
- Direct mode for small limits (`capacity < nodeCount x minimum batch`).
- `onFailure` when Redis is down: `local_fallback` (roughly `limit / nodeCount`), `open`, or `closed`.

Done when: Redis is called far less than once per request, and stopping Redis triggers local fallback.

### 7. Multi-node and load test

- Dockerize the three apps.
- Run two gateway instances.
- Load test with k6 in Docker.

Done when: combined admits across both gateways stay within the limit, and p95 added latency is measured.

### 8. Optional: Redis Cluster

- Compose setup with a Redis Cluster. The key design is already cluster-safe.

## Assumptions

- Tests use Testcontainers, so they need Docker running.
- Identity comes from headers: `X-User-Id`, `X-Api-Key`, or the remote address for `ip`. Real authentication is out of scope.
- Control plane authorization is a static token per `serviceId` in config, standing in for real authz.
- `nodeCount` is a config property locally, standing in for service discovery.
- Spring Boot version is whatever start.spring.io currently generates for Java 21.
- The built-in Spring Cloud Gateway rate limiter is not used.

## Open decisions (from the design)

- Accuracy: acceptable overshoot.
- Capacity: active keys x ~100 bytes, giving Redis memory and shard count.
- Redis failover: losing recent counters is acceptable.
- Multi-region: limits per region or global.
- IP identity: NAT and `X-Forwarded-For` spoofing.
