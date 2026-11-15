package com.ratelimiter.gateway.limiter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ratelimiter.gateway.rules.Rule;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/** Shared helpers for tests that drive the limiter directly (no Spring context). */
final class LimiterTestSupport {

    private LimiterTestSupport() {
    }

    /** A TokenBucketClient that counts the Redis calls made through it and the tokens Redis granted. */
    static final class CountingClient extends TokenBucketClient {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong granted = new AtomicLong();
        final List<Long> wants = new CopyOnWriteArrayList<>();

        CountingClient(ReactiveStringRedisTemplate template) {
            super(template);
        }

        @Override
        public Mono<Decision> acquire(String key, long capacity, double refillPerSec, long want) {
            calls.incrementAndGet();
            wants.add(want);
            return super.acquire(key, capacity, refillPerSec, want).doOnNext(d -> granted.addAndGet(d.granted()));
        }

        long maxWant() {
            return wants.stream().mapToLong(Long::longValue).max().orElse(0);
        }
    }

    static ReactiveStringRedisTemplate template(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        return new ReactiveStringRedisTemplate(factory);
    }

    static RlProperties props(int nodeCount, boolean leasing, Duration ttl, Duration denyMax, Duration timeout,
            Duration retryAfterFailure) {
        return new RlProperties(nodeCount, new RlProperties.Lease(leasing, 0.1, ttl, denyMax),
                new RlProperties.Redis(timeout, retryAfterFailure));
    }

    static Rule rule(long capacity, double refillPerSec, Rule.OnFailure onFailure) {
        return new Rule("svc", "r", new Rule.Match("*", "*"), Rule.KeyBy.USER_ID, capacity, refillPerSec, onFailure,
                true, 0L);
    }

    static Outcome check(LeasingLimiter limiter, String key, Rule rule) {
        return limiter.check(key, rule).block(Duration.ofSeconds(10));
    }
}
