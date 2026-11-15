package com.ratelimiter.controlplane;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RuleRequest(
        @NotNull @Valid MatchRequest match,
        @NotNull KeyBy keyBy,
        @Min(1) long capacity,
        @DecimalMin(value = "0", inclusive = false) double refillPerSec,
        OnFailure onFailure,
        Boolean enabled) {

    public record MatchRequest(
            @NotNull @Pattern(regexp = "\\*|GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") String method,
            @NotNull @Pattern(regexp = "\\*|/.*") String path) {}
}
