package com.ratelimiter.gateway.limiter;

public final class BucketKey {

    private BucketKey() {
    }

    public static String of(String serviceId, String ruleId, String identity) {
        return "rl:" + serviceId + ":" + ruleId + ":" + identity;
    }
}
