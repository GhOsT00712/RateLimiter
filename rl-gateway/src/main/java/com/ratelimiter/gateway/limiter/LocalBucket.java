package com.ratelimiter.gateway.limiter;

/** In-memory token bucket used while Redis is unavailable. Starts full. */
final class LocalBucket {

    final long epoch;
    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long last;

    LocalBucket(double capacity, double refillPerSec, long epoch, long nowNanos) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSec / 1e9;
        this.epoch = epoch;
        this.tokens = capacity;
        this.last = nowNanos;
    }

    /** Returns 0 if a token was taken, else the milliseconds until one is available (at least 1). */
    synchronized long tryTake(long nowNanos) {
        tokens = Math.min(capacity, tokens + (nowNanos - last) * refillPerNano);
        last = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return Math.max(1, (long) Math.ceil((1 - tokens) / refillPerNano / 1e6));
    }
}
