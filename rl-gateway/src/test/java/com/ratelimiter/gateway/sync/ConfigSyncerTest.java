package com.ratelimiter.gateway.sync;

import static com.ratelimiter.gateway.sync.StubControlPlane.rule;
import static com.ratelimiter.gateway.sync.StubControlPlane.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import com.ratelimiter.gateway.rules.Rule;
import com.ratelimiter.gateway.rules.RuleTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class ConfigSyncerTest {

    private static final String V1 = snapshot("v1", rule("svc", "a", "/a/**", 1), rule("svc", "b", "/b/**", 2));

    @TempDir
    Path dir;

    StubControlPlane stub;
    ConfigSyncer syncer;
    Path file;

    @BeforeEach
    void setUp() {
        stub = new StubControlPlane("v1", V1);
        file = dir.resolve("data/rules-snapshot.json"); // parent does not exist yet
    }

    @AfterEach
    void tearDown() {
        if (syncer != null) {
            syncer.stop();
        }
        stub.stop();
    }

    private SyncProperties props() {
        return new SyncProperties(true, stub.url(), Duration.ofMillis(100), Duration.ZERO, Duration.ofMillis(50),
                Duration.ofMillis(200), Duration.ofSeconds(2), file);
    }

    private RuleTable table(String rulesFile) {
        return new RuleTable(new DefaultResourceLoader(), rulesFile, props());
    }

    private void start(RuleTable table) {
        syncer = new ConfigSyncer(table, props());
        syncer.start();
    }

    private static void await(BooleanSupplier cond) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static List<String> ids(RuleTable t) {
        return t.rules().stream().map(Rule::ruleId).toList();
    }

    private String read() {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private void assertNoTempFiles() throws IOException {
        try (Stream<Path> s = Files.list(file.getParent())) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactly("rules-snapshot.json");
        }
    }

    @Test
    void firstPollPopulatesTableAndWritesSnapshot() throws Exception {
        RuleTable t = table(null);
        assertThat(t.rules()).isEmpty();
        start(t);
        await(() -> "v1".equals(t.etag()));
        assertThat(ids(t)).containsExactly("a", "b");
        await(() -> read().equals(V1));
        assertNoTempFiles();
    }

    @Test
    void unchangedSnapshotGets304WithQuotedEtag() {
        RuleTable t = table(null);
        start(t);
        await(() -> stub.ifNoneMatch().size() >= 3);
        assertThat(stub.ifNoneMatch().get(0)).isEqualTo("<none>");
        assertThat(stub.ifNoneMatch().subList(1, 3)).containsOnly("\"v1\"");
        assertThat(t.etag()).isEqualTo("v1");
        assertThat(ids(t)).containsExactly("a", "b");
    }

    @Test
    void changesAreAppliedAndPersisted() throws Exception {
        RuleTable t = table(null);
        start(t);
        await(() -> "v1".equals(t.etag()));

        String v2 = snapshot("v2", rule("svc", "a", "/a/**", 10), rule("svc", "b", "/b/**", 2),
                rule("svc", "c", "/c/**", 3)); // a changed, c added
        stub.serve("v2", v2);
        await(() -> "v2".equals(t.etag()));
        assertThat(ids(t)).containsExactly("a", "b", "c");
        assertThat(t.rules().get(0).capacity()).isEqualTo(10);
        await(() -> read().equals(v2));

        String v3 = snapshot("v3", rule("svc", "c", "/c/**", 3)); // a, b removed
        stub.serve("v3", v3);
        await(() -> "v3".equals(t.etag()));
        assertThat(ids(t)).containsExactly("c");
        await(() -> read().equals(v3));
        assertNoTempFiles();
    }

    @Test
    void controlPlaneDownAtStartupUsesSnapshotFile() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, V1);
        stub.stop();
        RuleTable t = table(null);
        assertThat(t.etag()).isEqualTo("v1");
        start(t);
        Thread.sleep(300);
        assertThat(ids(t)).containsExactly("a", "b");
    }

    @Test
    void controlPlaneDownAndNoSnapshotMeansEmptyTable() throws Exception {
        stub.stop();
        RuleTable t = table(null);
        start(t);
        Thread.sleep(300);
        assertThat(t.rules()).isEmpty();
        assertThat(t.etag()).isNull();
        assertThat(syncer.isRunning()).isTrue();
    }

    @Test
    void corruptSnapshotFallsBackToRulesFileOrEmpty() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{corrupt");
        assertThat(table(null).rules()).isEmpty();
        assertThat(table("classpath:test-rules.json").etag()).isEqualTo("test");
    }

    @Test
    void snapshotFileWinsOverRulesFile() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, V1);
        assertThat(table("classpath:test-rules.json").etag()).isEqualTo("v1");
    }

    @Test
    void failuresKeepPreviousRulesAndRecovery() {
        RuleTable t = table(null);
        start(t);
        await(() -> "v1".equals(t.etag()));
        await(() -> read().equals(V1));

        for (StubControlPlane.Mode m : List.of(StubControlPlane.Mode.ERROR_500, StubControlPlane.Mode.MALFORMED)) {
            stub.mode(m);
            int before = stub.requests();
            await(() -> stub.requests() >= before + 3); // polled, with backoff, repeatedly
            assertThat(t.etag()).isEqualTo("v1");
            assertThat(ids(t)).containsExactly("a", "b");
        }

        // invalid path pattern: the snapshot parses but cannot be applied
        stub.mode(StubControlPlane.Mode.OK);
        stub.serve("v3", snapshot("v3", rule("svc", "bad", "/a/**/b", 1)));
        int before = stub.requests();
        await(() -> stub.requests() >= before + 3);
        assertThat(t.etag()).isEqualTo("v1");
        assertThat(ids(t)).containsExactly("a", "b");
        assertThat(read()).isEqualTo(V1);

        String v2 = snapshot("v2", rule("svc", "z", "/z/**", 7));
        stub.serve("v2", v2);
        await(() -> "v2".equals(t.etag()));
        assertThat(ids(t)).containsExactly("z");
        await(() -> read().equals(v2));
    }

    @Test
    void diskWriteFailureDoesNotUndoSwap() throws Exception {
        Files.writeString(dir.resolve("blocker"), "x");
        file = dir.resolve("blocker/sub/rules-snapshot.json"); // parent cannot be created
        RuleTable t = table(null);
        start(t);
        await(() -> "v1".equals(t.etag()));
        assertThat(ids(t)).containsExactly("a", "b");
    }

    @Test
    void stopEndsPolling() throws Exception {
        start(table(null));
        await(() -> stub.requests() >= 2);
        syncer.stop();
        Thread.sleep(200); // let an in-flight request drain
        int after = stub.requests();
        Thread.sleep(500);
        assertThat(stub.requests()).isEqualTo(after);
        assertThat(syncer.isRunning()).isFalse();
    }
}
