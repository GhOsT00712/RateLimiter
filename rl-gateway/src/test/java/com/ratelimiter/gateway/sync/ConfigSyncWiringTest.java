package com.ratelimiter.gateway.sync;

import static com.ratelimiter.gateway.sync.StubControlPlane.rule;
import static com.ratelimiter.gateway.sync.StubControlPlane.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.ratelimiter.gateway.rules.RuleTable;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConfigSyncWiringTest {

    private static final String BODY = snapshot("sync-1", rule("synced", "global-2", "/synced/**", 2));
    private static final StubControlPlane CONTROL_PLANE = new StubControlPlane("sync-1", BODY);
    private static final HttpServer BACKEND = startBackend();

    // Static so the DynamicPropertySource can see it; JUnit injects it before the Spring context is built.
    @TempDir
    static Path dir;

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("rl.sync.enabled", () -> "true");
        registry.add("rl.sync.control-plane-url", CONTROL_PLANE::url);
        registry.add("rl.sync.interval", () -> "200ms");
        registry.add("rl.sync.jitter", () -> "0");
        registry.add("rl.sync.snapshot-file", () -> dir.resolve("rules-snapshot.json").toString());
        String p = "spring.cloud.gateway.server.webflux.routes[0]";
        registry.add(p + ".id", () -> "synced");
        registry.add(p + ".uri", () -> "http://localhost:" + BACKEND.getAddress().getPort());
        registry.add(p + ".predicates[0]", () -> "Path=/synced/**");
    }

    @LocalServerPort
    int port;

    @Autowired
    RuleTable table;

    @AfterAll
    static void stop() {
        BACKEND.stop(0);
        CONTROL_PLANE.stop();
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", ex -> {
                byte[] body = "{}".getBytes();
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rulesArriveViaSyncAndAreEnforced() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!"sync-1".equals(table.etag()) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(table.etag()).isEqualTo("sync-1");
        assertThat(table.rules()).hasSize(1);

        Path snapshot = dir.resolve("rules-snapshot.json");
        deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(snapshot) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(Files.readString(snapshot)).isEqualTo(BODY);

        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.get().uri("/synced/x").exchange().expectStatus().isOk();
        client.get().uri("/synced/x").exchange().expectStatus().isOk();
        client.get().uri("/synced/x").exchange().expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.ruleId").isEqualTo("global-2");
    }
}
