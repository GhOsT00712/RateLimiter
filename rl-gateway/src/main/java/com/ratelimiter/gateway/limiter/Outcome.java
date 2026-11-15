package com.ratelimiter.gateway.limiter;

/** The result of checking one rule for one request. */
public record Outcome(Kind kind, long remaining, long retryAfterMs) {

    public enum Kind {
        /** Allowed; {@code remaining} is a best-effort count for the headers. */
        ALLOWED,
        /** Allowed because Redis failed (OPEN, or a local fallback bucket); no rate-limit headers. */
        ALLOWED_NO_HEADERS,
        /** Rate limited: 429. */
        DENIED,
        /** Redis failed and the rule is CLOSED: 503. */
        UNAVAILABLE
    }

    static Outcome allowed(long remaining) {
        return new Outcome(Kind.ALLOWED, remaining, 0);
    }

    static Outcome allowedNoHeaders() {
        return new Outcome(Kind.ALLOWED_NO_HEADERS, 0, 0);
    }

    static Outcome denied(long retryAfterMs) {
        return new Outcome(Kind.DENIED, 0, retryAfterMs);
    }

    static Outcome unavailable() {
        return new Outcome(Kind.UNAVAILABLE, 0, 0);
    }

    public boolean rejected() {
        return kind == Kind.DENIED || kind == Kind.UNAVAILABLE;
    }
}
