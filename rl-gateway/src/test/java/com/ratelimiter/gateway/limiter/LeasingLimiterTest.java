package com.ratelimiter.gateway.limiter;

import static com.ratelimiter.gateway.limiter.LimiterTestSupport.check;
import static com.ratelimiter.gateway.limiter.LimiterTestSupport.props;
import static com.ratelimiter.gateway.limiter.LimiterTestSupport.rule;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.ratelimiter.gateway.limiter.LimiterTestSupport.CountingClient;
import com.ratelimiter.gateway.rules.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Testcontainers
class LeasingLimiterTest {

    private static final double NO_REFILL = 0.001;
    private static final Rule.OnFailure OPEN = Rule.OnFailure.OPEN;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static ReactiveStringRedisTemplate template;

    private final List<LeasingLimiter> created = new ArrayList<>();

    @BeforeAll
    static void connect() {
        template = LimiterTestSupport.template(redis.getHost(), redis.getMappedPort(6379));
    }

    @AfterEach
    void stopSweepers() {
        created.forEach(LeasingLimiter::destroy);
    }

    private LeasingLimiter limiter(CountingClient client, int nodeCount, boolean leasing, long ttlMs, long denyMs) {
        var l = new LeasingLimiter(client, props(nodeCount, leasing, Duration.ofMillis(ttlMs),
                Duration.ofMillis(denyMs), Duration.ofSeconds(2), Duration.ofSeconds(1)));
        created.add(l);
        return l;
    }

    private static String key() {
        return BucketKey.of("svc", "r", UUID.randomUUID().toString());
    }

    @Test
    void hotKeyUsesFewRedisCallsAndBatchGrowsToMaxBatchOnly() {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, true, 10_000, 1_000);
        Rule rule = rule(1000, NO_REFILL, OPEN); // maxBatch = min(100, 1000)
        assertThat(limiter.maxBatch(rule)).isEqualTo(100);
        String k = key();
        for (int i = 0; i < 1000; i++) {
            assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
        }
        assertThat(client.calls.get()).isLessThan(150);
        assertThat(client.calls.get()).isLessThan(30); // 1+2+4+..+64 = 127, then 100 each: about 16
        assertThat(client.maxWant()).isEqualTo(100);
        // doubling: no lease more than twice its predecessor
        for (int i = 1; i < client.wants.size(); i++) {
            assertThat(client.wants.get(i)).isLessThanOrEqualTo(2 * client.wants.get(i - 1));
        }
        assertThat(client.wants.get(0)).isEqualTo(1);
        assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.DENIED);
    }

    @Test
    void smallLimitBehavesAsDirectMode() {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, true, 10_000, 10_000);
        Rule rule = rule(5, NO_REFILL, OPEN);
        String k = key();
        for (int i = 0; i < 5; i++) {
            assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
            assertThat(client.calls.get()).isEqualTo(i + 1);
        }
        assertThat(client.wants).containsOnly(1L);
        assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.DENIED);
        assertThat(client.calls.get()).isEqualTo(6);
    }

    @Test
    void lowTrafficKeyKeepsBatchOneAndLosesNoTokens() throws Exception {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, true, 100, 1_000);
        Rule rule = rule(100, NO_REFILL, OPEN); // maxBatch 10
        String k = key();
        for (int i = 0; i < 6; i++) {
            assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
            Thread.sleep(250); // beyond the 100 ms lease TTL: idle
        }
        assertThat(client.wants).containsOnly(1L);
        assertThat(client.calls.get()).isEqualTo(6);
        // exactly 6 tokens were taken from Redis: 94 remain
        assertThat(new TokenBucketClient(template).acquire(k, 100, NO_REFILL, 1000).block().granted()).isEqualTo(94);
    }

    @Test
    void expiredLeaseLeftoversAreDroppedAndBatchResets() throws Exception {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, true, 600, 1_000);
        Rule rule = rule(1000, NO_REFILL, OPEN);
        String k = key();
        for (int i = 0; i < 4; i++) { // leases of 1, 2 (used up), then 4 with 3 left in hand
            check(limiter, k, rule);
        }
        assertThat(client.wants).containsExactly(1L, 2L, 4L);
        assertThat(limiter.nextBatch(k)).isEqualTo(4);
        Thread.sleep(300);
        check(limiter, k, rule); // still inside the TTL: from hand, no Redis call
        assertThat(client.calls.get()).isEqualTo(3);
        Thread.sleep(800); // lease now expired (and the key idle)
        int before = client.calls.get();
        assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
        assertThat(client.calls.get()).isEqualTo(before + 1); // leftovers were not usable
        assertThat(client.wants.get(client.wants.size() - 1)).isEqualTo(1L);
    }

    @Test
    void singleFlightOnColdKey() {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, true, 10_000, 1_000);
        Rule rule = rule(10_000, NO_REFILL, OPEN);
        String k = key();
        int n = 200;
        List<Outcome> outcomes = Flux.range(0, n)
                .flatMap(i -> limiter.check(k, rule).subscribeOn(Schedulers.parallel()), n)
                .collectList().block(Duration.ofSeconds(20));
        long allowed = outcomes.stream().filter(o -> o.kind() == Outcome.Kind.ALLOWED).count();
        assertThat(allowed).isEqualTo(n);
        assertThat(allowed).isLessThanOrEqualTo(client.granted.get());
        assertThat(client.calls.get()).isLessThan(40);
    }

    @Test
    void concurrentRequestsNeverAdmitMoreThanRedisGranted() {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, true, 10_000, 1_000);
        Rule rule = rule(300, NO_REFILL, OPEN); // fewer tokens than requests
        String k = key();
        List<Outcome> outcomes = Flux.range(0, 1000)
                .flatMap(i -> limiter.check(k, rule).subscribeOn(Schedulers.parallel()), 100)
                .collectList().block(Duration.ofSeconds(20));
        long allowed = outcomes.stream().filter(o -> o.kind() == Outcome.Kind.ALLOWED).count();
        assertThat(allowed).isLessThanOrEqualTo(client.granted.get());
        assertThat(client.granted.get()).isEqualTo(300);
        assertThat(allowed).isEqualTo(300L);
    }

    @Test
    void negativeCacheDeniesLocallyUntilWindowEnds() throws Exception {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, true, 10_000, 500);
        Rule rule = rule(20, NO_REFILL, OPEN); // maxBatch 2
        String k = key();
        for (int i = 0; i < 20; i++) {
            assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
        }
        Outcome first = check(limiter, k, rule);
        assertThat(first.kind()).isEqualTo(Outcome.Kind.DENIED);
        int callsAfterDenial = client.calls.get();
        for (int i = 0; i < 5; i++) {
            Outcome o = check(limiter, k, rule);
            assertThat(o.kind()).isEqualTo(Outcome.Kind.DENIED);
            assertThat(o.retryAfterMs()).isPositive();
        }
        assertThat(client.calls.get()).isEqualTo(callsAfterDenial);
        Thread.sleep(900);
        assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.DENIED);
        assertThat(client.calls.get()).isEqualTo(callsAfterDenial + 1);
    }

    @Test
    void twoNodesNeverExceedWhatRedisGrantsAndAreNotGrosslyUnder() throws Exception {
        var clientA = new CountingClient(template);
        var clientB = new CountingClient(template);
        var nodes = List.of(limiter(clientA, 2, true, 1_000, 1_000), limiter(clientB, 2, true, 1_000, 1_000));
        long capacity = 400;
        double refill = 200;
        Rule rule = rule(capacity, refill, OPEN); // maxBatch = min(40, 200) = 40
        String k = key();
        AtomicLong allowed = new AtomicLong();
        long runNanos = TimeUnit.SECONDS.toNanos(3);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        long start = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            LeasingLimiter node = nodes.get(t % 2);
            futures.add(pool.submit(() -> {
                while (System.nanoTime() - start < runNanos) {
                    if (check(node, k, rule).kind() == Outcome.Kind.ALLOWED) {
                        allowed.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        pool.shutdownNow();

        double ideal = capacity + refill * elapsed;
        long grantedByRedis = clientA.granted.get() + clientB.granted.get();
        // Never more than Redis granted, and Redis never grants more than the bucket allows (+2 for ms rounding).
        assertThat(allowed.get()).isLessThanOrEqualTo(grantedByRedis);
        assertThat(grantedByRedis).isLessThanOrEqualTo((long) ideal + 2);
        // Not grossly under: the offered load is far above the limit, so almost every token is spent. Slack of
        // 20 % plus the tokens that can legitimately sit unspent at the end (2 nodes x maxBatch 40).
        assertThat(allowed.get()).isGreaterThanOrEqualTo((long) (0.8 * ideal) - 80);
    }

    @Test
    void idleEntriesAreSwept() throws Exception {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, true, 200, 100); // idle after 5 x 200 ms
        new TokenBucketClient(template).acquire(key(), 5, NO_REFILL, 1).block(); // warm the connection
        Rule rule = rule(100, NO_REFILL, OPEN);
        for (int i = 0; i < 50; i++) {
            check(limiter, key(), rule);
        }
        assertThat(limiter.size()).isEqualTo(50);
        Thread.sleep(1500);
        limiter.sweep();
        assertThat(limiter.size()).isZero();
    }

    @Test
    void leaseDisabledMakesOneRedisCallPerRequest() {
        var client = new CountingClient(template);
        var limiter = limiter(client, 1, false, 1_000, 1_000);
        Rule rule = rule(1000, NO_REFILL, OPEN);
        String k = key();
        for (int i = 0; i < 20; i++) {
            Outcome o = check(limiter, k, rule);
            assertThat(o.kind()).isEqualTo(Outcome.Kind.ALLOWED);
            assertThat(o.remaining()).isEqualTo(999 - i);
        }
        assertThat(client.calls.get()).isEqualTo(20);
        assertThat(client.wants).containsOnly(1L);
        assertThat(limiter.size()).isZero();
    }
}
