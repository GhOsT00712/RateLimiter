package com.ratelimiter.controlplane;

import com.fasterxml.jackson.annotation.JsonValue;

public enum OnFailure {
    LOCAL_FALLBACK("local_fallback"), OPEN("open"), CLOSED("closed");

    private final String json;
    OnFailure(String json) { this.json = json; }
    @JsonValue public String json() { return json; }
}
