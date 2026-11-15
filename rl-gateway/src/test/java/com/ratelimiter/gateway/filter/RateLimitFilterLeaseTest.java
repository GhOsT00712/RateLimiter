package com.ratelimiter.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import com.ratelimiter.gateway.support.TestClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Leasing and Redis failure modes through the real gateway. Own Redis container (it gets paused). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RateLimitFilterLeaseTest {

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final HttpServer BACKEND = startBackend();

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static boolean paused;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        String uri = "http://localhost:" + BACKEND.getAddress().getPort();
        registry.add("rl.rules-file", () -> "classpath:test-rules-lease.json");
        registry.add("rl.sync.enabled", () -> "false");
        registry.add("rl.sync.snapshot-file", () -> "target/test-nonexistent/rules-snapshot-lease.json");
        registry.add("rl.node-count", () -> "2");
        registry.add("rl.lease.ttl", () -> "30s");
        registry.add("rl.redis.timeout", () -> "300ms");
        registry.add("rl.redis.retry-after-failure", () -> "500ms");
        List<String> services = List.of("leased", "closed", "fallback");
        for (int i = 0; i < services.size(); i++) {
            String s = services.get(i);
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + i + "]";
            registry.add(prefix + ".id", () -> s);
            registry.add(prefix + ".uri", () -> uri);
            registry.add(prefix + ".predicates[0]", () -> "Path=/" + s + "/**");
        }
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = TestClients.bindToServer(port);
        get("/closed/warm", UUID.randomUUID().toString()); // open the Redis connection while it is healthy
    }

    @AfterEach
    void resume() throws InterruptedException {
        if (paused) {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
            paused = false;
        }
        // wait for the circuit to close so the next test starts healthy
        for (int i = 0; i < 100; i++) {
            if (get("/closed/probe", UUID.randomUUID().toString()).returnResult(String.class)
                    .getStatus().value() == 200) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("gateway did not recover");
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", ex -> {
                HITS.incrementAndGet();
                byte[] body = "{}".getBytes();
                ex.getResponseHeaders().add("Content-Type", "application/json");
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

    private WebTestClient.ResponseSpec get(String path, String user) {
        return client.get().uri(path).header("X-User-Id", user).exchange();
    }

    private static void pause() {
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        paused = true;
    }

    @Test
    void leasedRuleReturnsSensibleHeadersAndThen429() {
        String u = UUID.randomUUID().toString();
        for (int k = 1; k <= 200; k++) {
            int expectedRemaining = 200 - k; // lease TTL is 30 s and the refill is negligible: exact
            get("/leased/x", u).expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Limit", "200")
                    .expectHeader().valueEquals("X-RateLimit-Remaining", Integer.toString(expectedRemaining))
                    .expectHeader().exists("X-RateLimit-Reset");
        }
        get("/leased/x", u).expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().value("Retry-After", v -> assertThat(Long.parseLong(v)).isGreaterThanOrEqualTo(1))
                .expectBody().jsonPath("$.error").isEqualTo("rate_limited");
    }

    @Test
    void closedRuleReturns503WithoutCallingBackendWhenRedisIsDown() {
        pause();
        int before = HITS.get();
        get("/closed/x", UUID.randomUUID().toString()).expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectHeader().valueEquals("Content-Type", "application/json")
                .expectBody().jsonPath("$.error").isEqualTo("rate_limiter_unavailable")
                .jsonPath("$.ruleId").isEqualTo("per-user");
        get("/closed/x", UUID.randomUUID().toString()).expectStatus().isEqualTo(503); // via the open circuit
        assertThat(HITS.get()).isEqualTo(before);
    }

    @Test
    void localFallbackAdmitsCapacityOverNodeCountThen429WhenRedisIsDown() {
        String u = UUID.randomUUID().toString();
        pause();
        for (int i = 0; i < 5; i++) { // capacity 10 / 2 nodes
            get("/fallback/x", u).expectStatus().isOk().expectHeader().doesNotExist("X-RateLimit-Limit");
        }
        get("/fallback/x", u).expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", v -> assertThat(Long.parseLong(v)).isGreaterThanOrEqualTo(1))
                .expectBody().jsonPath("$.error").isEqualTo("rate_limited");
    }
}
