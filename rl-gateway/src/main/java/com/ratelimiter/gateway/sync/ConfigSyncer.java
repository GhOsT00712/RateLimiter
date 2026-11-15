package com.ratelimiter.gateway.sync;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.ratelimiter.gateway.rules.RuleSnapshot;
import com.ratelimiter.gateway.rules.RuleTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Polls the control plane for the rule snapshot. One poll at a time: the next poll is scheduled only after the
 * current one has finished (success or failure). All result handling runs on the single scheduler thread, never on
 * a Netty event loop.
 */
@Component
@ConditionalOnProperty(prefix = "rl.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConfigSyncer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConfigSyncer.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RuleTable table;
    private final SyncProperties props;
    private final WebClient client;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rl-config-sync");
        t.setDaemon(true);
        return t;
    });

    private final Scheduler scheduler = Schedulers.fromExecutorService(executor);
    private volatile boolean running;
    private volatile Disposable inFlight;
    private int failures; // only touched on the scheduler thread

    public ConfigSyncer(RuleTable table, SyncProperties props) {
        this.table = table;
        this.props = props;
        this.client = WebClient.create(props.controlPlaneUrl());
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        schedule(0);
    }

    @Override
    public synchronized void stop() {
        running = false;
        Disposable d = inFlight;
        if (d != null) {
            d.dispose();
        }
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void schedule(long delayMs) {
        if (!running) {
            return;
        }
        try {
            executor.schedule(this::poll, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // shutting down
        }
    }

    private void poll() {
        if (!running) {
            return;
        }
        String etag = table.etag();
        inFlight = client.get().uri("/v1/rules/snapshot")
                .headers(h -> {
                    if (etag != null) {
                        h.set(HttpHeaders.IF_NONE_MATCH, "\"" + etag + "\"");
                    }
                })
                .exchangeToMono(resp -> {
                    int status = resp.statusCode().value();
                    if (status == 304) {
                        return resp.releaseBody().thenReturn(Optional.<byte[]>empty());
                    }
                    if (status == 200) {
                        return resp.bodyToMono(byte[].class).map(Optional::of);
                    }
                    return resp.releaseBody().then(Mono.error(new IllegalStateException("HTTP " + status)));
                })
                .timeout(props.requestTimeout())
                .publishOn(scheduler)
                .subscribe(this::onResponse, this::onFailure);
    }

    private void onResponse(Optional<byte[]> body) {
        try {
            body.ifPresent(this::apply);
        } catch (Exception e) {
            onFailure(e);
            return;
        }
        failures = 0;
        long jitter = props.jitter().toMillis();
        schedule(props.interval().toMillis() + (jitter > 0 ? ThreadLocalRandom.current().nextLong(jitter + 1) : 0));
    }

    private void apply(byte[] body) {
        RuleSnapshot snapshot = MAPPER.readValue(body, RuleSnapshot.class);
        String oldEtag = table.etag();
        table.replace(snapshot); // throws on an invalid rule; nothing is persisted then
        log.info("Rules changed: etag {} -> {} ({} rules)", oldEtag, snapshot.etag(), snapshot.rules().size());
        try {
            persist(body);
        } catch (Exception e) {
            log.warn("Could not write snapshot file {}: {}", props.snapshotFile(), e.toString());
        }
    }

    private void persist(byte[] body) throws IOException {
        Path target = props.snapshotFile().toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, body);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void onFailure(Throwable t) {
        failures++;
        long base = Math.min(props.backoffMax().toMillis(),
                props.backoffInitial().toMillis() << Math.min(failures - 1, 20));
        long delay = base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
        log.warn("Rule sync failed ({} consecutive): {}; keeping current rules, retrying in {} ms",
                failures, t.toString(), delay);
        schedule(delay);
    }
}
