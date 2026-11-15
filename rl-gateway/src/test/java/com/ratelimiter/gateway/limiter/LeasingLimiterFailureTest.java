package com.ratelimiter.gateway.limiter;

import static com.ratelimiter.gateway.limiter.LimiterTestSupport.check;
import static com.ratelimiter.gateway.limiter.LimiterTestSupport.rule;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import com.ratelimiter.gateway.limiter.LimiterTestSupport.CountingClient;
import com.ratelimiter.gateway.rules.Rule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real outages: the container is paused (connections stay open, nothing answers). Own container. */
@Testcontainers
class LeasingLimiterFailureTest {

    private static final double NO_REFILL = 0.001;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static CountingClient client;
    static LeasingLimiter limiter;
    static boolean paused;

    @BeforeAll
    static void setUp() {
        client = new CountingClient(LimiterTestSupport.template(redis.getHost(), redis.getMappedPort(6379)));
        // nodeCount 2 for the local fallback; timeout 300 ms; probe every 600 ms
        limiter = new LeasingLimiter(client, LimiterTestSupport.props(2, true, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofMillis(600)));
        check(limiter, key(), rule(1000, NO_REFILL, Rule.OnFailure.OPEN)); // open the connection
    }

    @AfterAll
    static void tearDown() {
        limiter.destroy();
    }

    @AfterEach
    void resume() throws InterruptedException {
        if (paused) {
            unpause();
        }
        // leave the circuit closed so each test starts from a healthy limiter
        awaitRecovery(rule(1000, NO_REFILL, Rule.OnFailure.OPEN));
    }

    private static String key() {
        return BucketKey.of("svc", "r", UUID.randomUUID().toString());
    }

    private static void pause() {
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        paused = true;
    }

    private static void unpause() {
        redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        paused = false;
    }

    /** Polls with fresh keys until the breaker lets a probe through and Redis answers. */
    private static void awaitRecovery(Rule rule) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (check(limiter, key(), rule).kind() == Outcome.Kind.ALLOWED) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("limiter did not recover within 20 s");
    }

    /** Runs {@code n} requests against the (down) limiter; returns the slowest in ms. */
    private static long slowest(int n, Rule rule, Outcome.Kind expected) {
        long worst = 0;
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            Outcome o = check(limiter, key(), rule);
            worst = Math.max(worst, (System.nanoTime() - t) / 1_000_000);
            assertThat(o.kind()).isEqualTo(expected);
        }
        return worst;
    }

    @Test
    void openAllowsWithoutHeadersFailsFastAndRecovers() throws Exception {
        Rule rule = rule(100, NO_REFILL, Rule.OnFailure.OPEN);
        pause();
        // first request pays the timeout, the rest are answered by the open circuit
        assertThat(check(limiter, key(), rule).kind()).isEqualTo(Outcome.Kind.ALLOWED_NO_HEADERS);
        int calls = client.calls.get();
        assertThat(slowest(20, rule, Outcome.Kind.ALLOWED_NO_HEADERS)).isLessThan(150); // timeout is 300
        assertThat(client.calls.get()).isLessThanOrEqualTo(calls + 2); // at most a probe
        unpause();
        awaitRecovery(rule);
        // normal limiting is back: a capacity-3 rule admits 3 then denies
        Rule small = rule(3, NO_REFILL, Rule.OnFailure.OPEN);
        String k = key();
        for (int i = 0; i < 3; i++) {
            assertThat(check(limiter, k, small).kind()).isEqualTo(Outcome.Kind.ALLOWED);
        }
        assertThat(check(limiter, k, small).kind()).isEqualTo(Outcome.Kind.DENIED);
    }

    @Test
    void closedIsUnavailableFailsFastAndRecovers() throws Exception {
        Rule rule = rule(100, NO_REFILL, Rule.OnFailure.CLOSED);
        pause();
        assertThat(check(limiter, key(), rule).kind()).isEqualTo(Outcome.Kind.UNAVAILABLE);
        assertThat(slowest(20, rule, Outcome.Kind.UNAVAILABLE)).isLessThan(150);
        unpause();
        awaitRecovery(rule);
    }

    @Test
    void localFallbackAdmitsCapacityOverNodeCountThenDenies() throws Exception {
        Rule rule = rule(10, NO_REFILL, Rule.OnFailure.LOCAL_FALLBACK); // 10 / 2 nodes = 5 locally
        String k = key();
        pause();
        long start = System.nanoTime();
        int allowed = 0;
        Outcome lastDenied = null;
        for (int i = 0; i < 8; i++) {
            Outcome o = check(limiter, k, rule);
            if (o.kind() == Outcome.Kind.ALLOWED_NO_HEADERS) {
                allowed++;
            } else {
                assertThat(o.kind()).isEqualTo(Outcome.Kind.DENIED);
                lastDenied = o;
            }
        }
        // one timeout (300 ms) at most, then the circuit is open
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
        assertThat(allowed).isEqualTo(5);
        assertThat(lastDenied.retryAfterMs()).isPositive();
        unpause();
        awaitRecovery(rule);
        // after recovery the same key is limited by Redis again (its bucket there held 10 tokens)
        long ok = 0;
        for (int i = 0; i < 12; i++) {
            if (check(limiter, k, rule).kind() == Outcome.Kind.ALLOWED) {
                ok++;
            }
        }
        // The request that timed out had already been sent; Redis ran it on resume, so one token may be gone.
        assertThat(ok).isBetween(9L, 10L);
    }

    @Test
    void tokensInHandKeepWorkingWhileRedisIsDown() {
        Rule rule = rule(1000, NO_REFILL, Rule.OnFailure.CLOSED);
        String k = key();
        for (int i = 0; i < 3; i++) { // leases of 1, then 2 -> one token left in hand after the third request
            assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
        }
        // leases were 1, 2, then request 4 leases 4 (3 left): make sure something is in hand
        assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
        pause();
        long t = System.nanoTime();
        assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
        assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.ALLOWED);
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(150);
        // hand is empty after 3 more draws; then the rule's failure mode applies
        check(limiter, k, rule);
        assertThat(check(limiter, k, rule).kind()).isEqualTo(Outcome.Kind.UNAVAILABLE);
    }
}
