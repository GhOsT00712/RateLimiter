package com.ratelimiter.gateway.rules;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RuleSnapshot(String etag, List<Rule> rules) {

    public RuleSnapshot {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
