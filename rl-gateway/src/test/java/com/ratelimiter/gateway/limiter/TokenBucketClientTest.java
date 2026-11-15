package com.ratelimiter.gateway.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@SpringBootTest
@Testcontainers
class TokenBucketClientTest {

    private static final double NO_REFILL = 0.001;

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Autowired
    TokenBucketClient client;

    @Autowired
    ReactiveStringRedisTemplate template;

    private static String key() {
        return BucketKey.of("svc", "rule", UUID.randomUUID().toString());
    }

    private Decision acquire(String key, long cap, double rate, long want) {
        return client.acquire(key, cap, rate, want).block(Duration.ofSeconds(5));
    }

    @Test
    void burstThenDenied() {
        String k = key();
        for (int i = 0; i < 5; i++) {
            assertThat(acquire(k, 5, NO_REFILL, 1).allowed()).isTrue();
        }
        Decision d = acquire(k, 5, NO_REFILL, 1);
        assertThat(d.granted()).isZero();
        assertThat(d.allowed()).isFalse();
        assertThat(d.remaining()).isZero();
        assertThat(d.retryAfterMs()).isPositive();
    }

    @Test
    void remainingCountsDown() {
        String k = key();
        for (long expected = 4; expected >= 0; expected--) {
            Decision d = acquire(k, 5, NO_REFILL, 1);
            assertThat(d.granted()).isEqualTo(1);
            assertThat(d.remaining()).isEqualTo(expected);
            assertThat(d.retryAfterMs()).isZero();
        }
    }

    @Test
    void refillsOverTime() throws Exception {
        String k = key();
        assertThat(acquire(k, 10, 10, 10).granted()).isEqualTo(10);
        Thread.sleep(500);
        assertThat(acquire(k, 10, 10, 10).granted()).isBetween(3L, 7L);
    }

    @Test
    void neverExceedsCapacity() {
        assertThat(acquire(key(), 10, 10, 100).granted()).isEqualTo(10);
    }

    @Test
    void partialGrant() {
        String k = key();
        assertThat(acquire(k, 5, NO_REFILL, 8).granted()).isEqualTo(5);
        assertThat(acquire(k, 5, NO_REFILL, 1).granted()).isZero();
    }

    @Test
    void retryAfterIsPlausible() {
        String k = key();
        assertThat(acquire(k, 1, 2, 1).granted()).isEqualTo(1);
        Decision d = acquire(k, 1, 2, 1);
        assertThat(d.granted()).isZero();
        assertThat(d.retryAfterMs()).isBetween(1L, 500L);
    }

    @Test
    void keyHasTtl() {
        String k = key();
        acquire(k, 10, 2, 1);
        Long pttl = template.getConnectionFactory().getReactiveConnection()
                .keyCommands().pTtl(java.nio.ByteBuffer.wrap(k.getBytes()))
                .block(Duration.ofSeconds(5));
        assertThat(pttl).isPositive().isLessThanOrEqualTo(5000L);
    }

    @Test
    void concurrentCallsAreAtomic() {
        String k = key();
        List<Decision> results = Flux.range(0, 50)
                .flatMap(i -> client.acquire(k, 20, NO_REFILL, 1).subscribeOn(Schedulers.parallel()), 50)
                .collectList()
                .block(Duration.ofSeconds(10));
        assertThat(results).hasSize(50);
        assertThat(results.stream().filter(Decision::allowed).count()).isEqualTo(20);
    }

    @Test
    void separateKeysAreIndependent() {
        String a = key();
        String b = key();
        assertThat(acquire(a, 1, NO_REFILL, 1).allowed()).isTrue();
        assertThat(acquire(a, 1, NO_REFILL, 1).allowed()).isFalse();
        assertThat(acquire(b, 1, NO_REFILL, 1).allowed()).isTrue();
    }

    @Test
    void invalidArgumentsRejected() {
        assertThatThrownBy(() -> client.acquire("k", 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.acquire("k", 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.acquire("k", 1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.acquire("k", 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
