package com.ratelimiter.gateway.rules;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ratelimiter.gateway.sync.SyncProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RuleTable {

    private static final Logger log = LoggerFactory.getLogger(RuleTable.class);
    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final Comparator<Rule> MOST_RESTRICTIVE =
            Comparator.comparingLong(Rule::capacity).thenComparingDouble(Rule::refillPerSec);

    /** A rule with its path pattern parsed once. A null pattern means "any path". */
    private record Compiled(Rule rule, PathPattern pattern) {
    }

    private record State(String etag, List<Rule> rules, List<Compiled> compiled) {
    }

    private final AtomicReference<State> state = new AtomicReference<>(new State(null, List.of(), List.of()));

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Rules from {@code rl.rules-file} only (no last-known-good snapshot). */
    public RuleTable(ResourceLoader loader, String rulesFile) {
        this(loader, rulesFile, null);
    }

    /**
     * Startup order: last-known-good snapshot file, else {@code rl.rules-file}, else empty. Never fails startup
     * because of a bad file.
     */
    @Autowired
    public RuleTable(ResourceLoader loader, @Value("${rl.rules-file:}") String rulesFile, SyncProperties sync) {
        if (sync != null && loadSnapshotFile(sync.snapshotFile())) {
            return;
        }
        if (rulesFile == null || rulesFile.isBlank()) {
            log.info("No rules found at startup; starting with an empty rule table");
            return;
        }
        Resource resource = loader.getResource(rulesFile);
        if (!resource.exists()) {
            log.warn("Rules file {} not found; starting with an empty rule table", rulesFile);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            replace(MAPPER.readValue(in, RuleSnapshot.class));
            log.info("Loaded {} rules from {}", state.get().rules().size(), rulesFile);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read rules file " + rulesFile, e);
        }
    }

    private boolean loadSnapshotFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            replace(MAPPER.readValue(Files.readAllBytes(file), RuleSnapshot.class));
            log.info("Loaded {} rules (etag {}) from snapshot file {}", state.get().rules().size(),
                    state.get().etag(), file);
            return true;
        } catch (Exception e) {
            log.warn("Ignoring unreadable snapshot file {}: {}", file, e.toString());
            return false;
        }
    }

    public void replace(RuleSnapshot snapshot) {
        List<Compiled> compiled = snapshot.rules().stream()
                .map(r -> new Compiled(r, "*".equals(r.match().path()) ? null : PARSER.parse(r.match().path())))
                .toList();
        state.set(new State(snapshot.etag(), snapshot.rules(), compiled));
    }

    public String etag() {
        return state.get().etag();
    }

    public List<Rule> rules() {
        return state.get().rules();
    }

    /** Enabled rules of the service that match the request, most restrictive first. */
    public List<Rule> matching(String serviceId, HttpMethod method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return state.get().compiled().stream()
                .filter(c -> c.rule().enabled() && serviceId.equals(c.rule().serviceId()))
                .filter(c -> methodMatches(c.rule().match().method(), method))
                .filter(c -> c.pattern() == null || c.pattern().matches(container))
                .map(Compiled::rule)
                .sorted(MOST_RESTRICTIVE)
                .toList();
    }

    private static boolean methodMatches(String pattern, HttpMethod method) {
        return "*".equals(pattern) || pattern.equalsIgnoreCase(method.name());
    }
}
