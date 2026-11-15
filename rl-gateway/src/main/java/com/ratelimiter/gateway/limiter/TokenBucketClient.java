package com.ratelimiter.gateway.limiter;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class TokenBucketClient {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final RedisScript<List<Long>> SCRIPT =
            (RedisScript) RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final ReactiveStringRedisTemplate redis;

    public TokenBucketClient(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<Decision> acquire(String key, long capacity, double refillPerSec, long want) {
        if (capacity < 1 || !(refillPerSec > 0) || want < 1) {
            throw new IllegalArgumentException("capacity>=1, refillPerSec>0, want>=1 required");
        }
        return redis.execute(SCRIPT, List.of(key),
                        List.of(Long.toString(capacity), Double.toString(refillPerSec), Long.toString(want)))
                .next()
                .map(r -> new Decision(r.get(0), r.get(1), r.get(2)));
    }
}
