package com.ratelimiter.gateway.limiter;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("rl")
public record RlProperties(
        /* Number of gateway nodes sharing the limits; stands in for service discovery. */
        @DefaultValue("1") int nodeCount,
        @DefaultValue Lease lease,
        @DefaultValue Redis redis) {

    public record Lease(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.1") double batchFraction,
            @DefaultValue("1s") Duration ttl,
            @DefaultValue("1s") Duration denyCacheMax) {
    }

    public record Redis(
            @DefaultValue("100ms") Duration timeout,
            @DefaultValue("1s") Duration retryAfterFailure) {
    }
}
