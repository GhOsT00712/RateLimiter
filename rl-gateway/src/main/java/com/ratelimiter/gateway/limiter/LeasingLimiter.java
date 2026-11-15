package com.ratelimiter.gateway.limiter;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.ratelimiter.gateway.rules.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Per-node token leasing in front of {@link TokenBucketClient}, plus the Redis failure modes.
 *
 * <p>Accuracy: every token this class admits was granted by Redis (or comes from a local fallback bucket while
 * Redis is down), and a leased token is only usable for {@code ttl} after the lease call was sent. So in any
 * window of length L the node group admits at most {@code capacity + refill * (L + ttl)} tokens per key, and
 * dropped leftovers can only make it under-admit.
 */
@Component
public class LeasingLimiter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LeasingLimiter.class);

    /** Shared flight rounds before a request makes its own, unshared Redis call. Doubling batches need ~log2(maxBatch) rounds on a cold burst. */
    private static final int MAX_ROUNDS = 10;

    /** Entries idle for this many lease TTLs are swept. */
    private static final int IDLE_TTLS = 5;

    private static final class CircuitOpen extends RuntimeException {
        CircuitOpen() {
            super("redis circuit open", null, false, false);
        }
    }

    private static final CircuitOpen CIRCUIT_OPEN = new CircuitOpen();

    /** Local state of one bucket key. All fields are guarded by {@code synchronized (entry)}. */
    private static final class Entry {
        long tokens;          // leased tokens in hand
        long leasedAt;        // nanoTime the lease call was sent (conservative: the grant happened after this)
        long requested = 1;   // batch size of the current/last lease call
        long nextBatch = 1;
        long lastRemaining;   // `remaining` reported by the last Redis reply
        long deniedUntil;     // nanoTime; denied locally while now < deniedUntil
        long lastUsed;
        Mono<Decision> inflight;
        LocalBucket fallback;

        Entry(long now) {
            leasedAt = now;
            deniedUntil = now;
            lastUsed = now;
        }

        synchronized boolean idle(long now, long idleNanos) {
            return inflight == null && now - lastUsed > idleNanos;
        }
    }

    private final TokenBucketClient client;
    private final boolean leasing;
    private final int nodeCount;
    private final double batchFraction;
    private final long ttlNanos;
    private final long denyMaxNanos;
    private final Duration redisTimeout;
    private final long retryAfterFailureNanos;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    // circuit breaker
    private final AtomicBoolean down = new AtomicBoolean();
    private final AtomicLong probeAt = new AtomicLong(); // nanoTime; meaningful only while down
    private final AtomicLong recoveries = new AtomicLong();

    public LeasingLimiter(TokenBucketClient client, RlProperties props) {
        this.client = client;
        this.leasing = props.lease().enabled();
        this.nodeCount = Math.max(1, props.nodeCount());
        this.batchFraction = props.lease().batchFraction();
        this.ttlNanos = props.lease().ttl().toNanos();
        this.denyMaxNanos = props.lease().denyCacheMax().toNanos();
        this.redisTimeout = props.redis().timeout();
        this.retryAfterFailureNanos = props.redis().retryAfterFailure().toNanos();
        long periodMs = Math.max(50, props.lease().ttl().toMillis());
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rl-lease-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        sweeper.shutdownNow();
    }

    public Mono<Outcome> check(String key, Rule rule) {
        return Mono.defer(() -> {
            if (!leasing) {
                return direct(key, rule);
            }
            Entry e = entries.computeIfAbsent(key, k -> new Entry(System.nanoTime()));
            return attempt(e, key, rule, 0);
        });
    }

    /** Lease disabled, or the last resort of a contended lease: one Redis call for one token. */
    private Mono<Outcome> direct(String key, Rule rule) {
        return call(key, rule, 1)
                .map(d -> d.allowed() ? Outcome.allowed(d.remaining()) : Outcome.denied(d.retryAfterMs()))
                .onErrorResume(t -> Mono.just(failure(key, rule)));
    }

    /**
     * Rules too small to lease from (maxBatch 1) behave as in direct mode: one Redis call per request, in
     * parallel (single flight would only serialize them), plus the negative cache for denials.
     */
    private Mono<Outcome> directCached(Entry e, String key, Rule rule, long now) {
        synchronized (e) {
            e.lastUsed = now;
            if (e.deniedUntil - now > 0) {
                return Mono.just(Outcome.denied(ceilMillis(e.deniedUntil - now)));
            }
        }
        return call(key, rule, 1)
                .map(d -> {
                    if (d.allowed()) {
                        return Outcome.allowed(d.remaining());
                    }
                    synchronized (e) {
                        e.deniedUntil = now + Math.min(d.retryAfterMs() * 1_000_000L, denyMaxNanos);
                    }
                    return Outcome.denied(d.retryAfterMs());
                })
                .onErrorResume(t -> Mono.just(failure(key, rule)));
    }

    private Mono<Outcome> attempt(Entry e, String key, Rule rule, int round) {
        if (round >= MAX_ROUNDS) {
            return direct(key, rule);
        }
        long now = System.nanoTime();
        long maxBatch = maxBatch(rule);
        if (maxBatch == 1) {
            return directCached(e, key, rule, now);
        }
        Mono<Decision> flight;
        Sinks.One<Decision> sink = null;
        long batch = 0;
        synchronized (e) {
            boolean idle = now - e.lastUsed > ttlNanos;
            e.lastUsed = now;
            if (e.tokens > 0 && now - e.leasedAt >= ttlNanos) {
                e.tokens = 0; // expired leftovers: already deducted in Redis, so dropping only under-admits
                e.nextBatch = 1;
            }
            if (e.tokens > 0) {
                e.tokens--;
                if (e.tokens == 0) {
                    e.nextBatch = Math.min(maxBatch, e.requested * 2); // used up before expiry: lease more
                }
                return Mono.just(Outcome.allowed(e.lastRemaining + e.tokens));
            }
            if (e.deniedUntil - now > 0) {
                return Mono.just(Outcome.denied(ceilMillis(e.deniedUntil - now)));
            }
            if (e.inflight != null) {
                flight = e.inflight;
            } else {
                if (idle) {
                    e.nextBatch = 1;
                }
                batch = Math.max(1, Math.min(e.nextBatch, maxBatch));
                e.requested = batch;
                sink = Sinks.one();
                flight = e.inflight = sink.asMono();
            }
        }

        if (sink != null) {
            // Single flight: this request owns the lease call; later requests for the key wait on the sink.
            // The call is subscribed here, not through the request's own subscription, so a client that
            // disconnects cannot cancel a lease other requests are waiting for (the timeout bounds it).
            Sinks.One<Decision> s = sink;
            call(key, rule, batch).subscribe(
                    d -> {
                        onLease(e, d, now, maxBatch);
                        s.tryEmitValue(d);
                    },
                    t -> {
                        synchronized (e) {
                            e.inflight = null;
                        }
                        s.tryEmitError(t);
                    });
            // onLease already reserved this request's own token, so waiters cannot steal it.
            return flight
                    .map(d -> d.allowed() ? Outcome.allowed(remainingNow(e)) : Outcome.denied(d.retryAfterMs()))
                    .onErrorResume(t -> Mono.just(failure(key, rule)));
        }
        return flight
                .flatMap(d -> d.allowed() ? attempt(e, key, rule, round + 1) : Mono.just(Outcome.denied(d.retryAfterMs())))
                .onErrorResume(t -> Mono.just(failure(key, rule)));
    }

    /** Applies a lease reply. {@code sentAt} is when the call was sent; the grant happened after it. */
    private void onLease(Entry e, Decision d, long sentAt, long maxBatch) {
        synchronized (e) {
            e.inflight = null;
            e.lastRemaining = d.remaining();
            if (d.granted() > 0) {
                e.tokens = d.granted() - 1; // one is for the request that made the call
                e.leasedAt = sentAt;
                if (e.tokens == 0) {
                    e.nextBatch = Math.min(maxBatch, e.requested * 2);
                }
            } else {
                // Negative cache. Never longer than Redis's own retry hint, so no token is withheld that
                // Redis would have granted.
                long hold = Math.min(d.retryAfterMs() * 1_000_000L, denyMaxNanos);
                e.deniedUntil = sentAt + hold;
            }
        }
    }

    private static long remainingNow(Entry e) {
        synchronized (e) {
            return e.lastRemaining + e.tokens;
        }
    }

    long maxBatch(Rule rule) {
        return Math.max(1, Math.min((long) Math.floor(rule.capacity() * batchFraction), rule.capacity() / nodeCount));
    }

    private static long ceilMillis(long nanos) {
        return Math.max(1, (nanos + 999_999) / 1_000_000);
    }

    // ---- Redis failure handling

    private Outcome failure(String key, Rule rule) {
        Rule.OnFailure mode = rule.onFailure() == null ? Rule.OnFailure.OPEN : rule.onFailure();
        return switch (mode) {
            case OPEN -> Outcome.allowedNoHeaders();
            case CLOSED -> Outcome.unavailable();
            case LOCAL_FALLBACK -> {
                Entry e = entries.computeIfAbsent(key, k -> new Entry(System.nanoTime()));
                long now = System.nanoTime();
                long retryMs;
                synchronized (e) {
                    e.lastUsed = now;
                    long epoch = recoveries.get(); // a bucket from before the last recovery is stale
                    if (e.fallback == null || e.fallback.epoch != epoch) {
                        e.fallback = new LocalBucket(Math.max(1, rule.capacity() / nodeCount),
                                rule.refillPerSec() / nodeCount, epoch, now);
                    }
                    retryMs = e.fallback.tryTake(now);
                }
                yield retryMs == 0 ? Outcome.allowedNoHeaders() : Outcome.denied(retryMs);
            }
        };
    }

    /** One guarded Redis call: circuit breaker, timeout, and state-change logging. */
    private Mono<Decision> call(String key, Rule rule, long want) {
        return Mono.defer(() -> {
            if (!breakerAllows()) {
                return Mono.<Decision>error(CIRCUIT_OPEN);
            }
            return client.acquire(key, rule.capacity(), rule.refillPerSec(), want)
                    .timeout(redisTimeout)
                    .switchIfEmpty(Mono.error(() -> new IllegalStateException("empty reply from redis")))
                    .doOnNext(d -> succeeded())
                    .doOnError(this::failed);
        });
    }

    private boolean breakerAllows() {
        if (!down.get()) {
            return true;
        }
        long now = System.nanoTime();
        long at = probeAt.get();
        // Exactly one caller wins the CAS and probes; the rest keep failing fast until the probe settles.
        return now - at >= 0 && probeAt.compareAndSet(at, now + retryAfterFailureNanos);
    }

    private void failed(Throwable t) {
        probeAt.set(System.nanoTime() + retryAfterFailureNanos);
        if (down.compareAndSet(false, true)) {
            log.warn("Redis unavailable, applying per-rule failure modes: {}", t.toString());
        }
    }

    private void succeeded() {
        if (down.get() && down.compareAndSet(true, false)) {
            recoveries.incrementAndGet();
            log.info("Redis recovered, normal rate limiting resumed");
        }
    }

    // ---- housekeeping

    void sweep() {
        long now = System.nanoTime();
        long idleNanos = IDLE_TTLS * ttlNanos;
        for (String k : entries.keySet()) {
            // Atomic with respect to other users of the map entry; a request racing the removal at worst
            // works on a detached entry, which cannot admit anything Redis did not grant.
            entries.computeIfPresent(k, (key, e) -> e.idle(now, idleNanos) ? null : e);
        }
    }

    int size() {
        return entries.size();
    }

    /** The batch size the next lease call for the key would use; 0 if the key has no entry. */
    long nextBatch(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return 0;
        }
        synchronized (e) {
            return e.nextBatch;
        }
    }
}
