package com.ratelimiter.gateway.sync;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("rl.sync")
public record SyncProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:8081") String controlPlaneUrl,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("30s") Duration jitter,
        @DefaultValue("1s") Duration backoffInitial,
        @DefaultValue("60s") Duration backoffMax,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("./data/rl-gateway/rules-snapshot.json") Path snapshotFile) {
}
