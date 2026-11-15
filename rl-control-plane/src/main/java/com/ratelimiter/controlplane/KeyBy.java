package com.ratelimiter.controlplane;

import com.fasterxml.jackson.annotation.JsonValue;

public enum KeyBy {
    USER_ID("userId"), API_KEY("apiKey"), IP("ip"), GLOBAL("global");

    private final String json;
    KeyBy(String json) { this.json = json; }
    @JsonValue public String json() { return json; }
}
