package com.ratelimiter.controlplane;

import java.time.Instant;

public record RuleResponse(String serviceId, String ruleId, Match match, KeyBy keyBy, long capacity,
                           double refillPerSec, OnFailure onFailure, boolean enabled, Long version,
                           Instant updatedAt) {

    static RuleResponse from(Rule r) {
        return new RuleResponse(r.getServiceId(), r.getRuleId(), r.getMatch(), r.getKeyBy(), r.getCapacity(),
                r.getRefillPerSec(), r.getOnFailure(), r.isEnabled(), r.getVersion(), r.getUpdatedAt());
    }
}
