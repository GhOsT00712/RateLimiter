package com.ratelimiter.gateway.limiter;

public record Decision(long granted, long remaining, long retryAfterMs) {

    public boolean allowed() {
        return granted > 0;
    }
}
