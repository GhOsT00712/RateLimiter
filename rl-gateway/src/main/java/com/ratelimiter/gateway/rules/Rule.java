package com.ratelimiter.gateway.rules;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Rule(
        String serviceId,
        String ruleId,
        Match match,
        KeyBy keyBy,
        long capacity,
        double refillPerSec,
        OnFailure onFailure,
        boolean enabled,
        Long version) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Match(String method, String path) {
    }

    public enum KeyBy {
        @JsonProperty("userId") USER_ID,
        @JsonProperty("apiKey") API_KEY,
        @JsonProperty("ip") IP,
        @JsonProperty("global") GLOBAL
    }

    public enum OnFailure {
        @JsonProperty("local_fallback") LOCAL_FALLBACK,
        @JsonProperty("open") OPEN,
        @JsonProperty("closed") CLOSED
    }
}
