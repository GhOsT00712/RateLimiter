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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RateLimitFilterTest {

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final HttpServer BACKEND = startBackend();

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        String uri = "http://localhost:" + BACKEND.getAddress().getPort();
        registry.add("rl.rules-file", () -> "classpath:test-rules.json");
        registry.add("rl.sync.enabled", () -> "false");
        registry.add("rl.sync.snapshot-file", () -> "target/test-nonexistent/rules-snapshot.json");
        String p = "spring.cloud.gateway.server.webflux.routes[";
        // These replace the route list from application.yml wholesale, so "orders" is redeclared.
        List<String> services = List.of("orders", "anon", "apikey", "byip", "glob", "multi", "disabled", "mixed");
        for (int i = 0; i < services.size(); i++) {
            String s = services.get(i);
            String prefix = p + i + "]";
            registry.add(prefix + ".id", () -> s);
            registry.add(prefix + ".uri", () -> uri);
            registry.add(prefix + ".predicates[0]", () -> "Path=/" + ("orders".equals(s) ? "v1/orders" : s) + "/**");
        }
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = TestClients.bindToServer(port);
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

    private WebTestClient.ResponseSpec get(String path, String... headers) {
        var spec = client.get().uri(path);
        for (int i = 0; i < headers.length; i += 2) {
            spec = spec.header(headers[i], headers[i + 1]);
        }
        return spec.exchange();
    }

    private static String user() {
        return UUID.randomUUID().toString();
    }

    @Test
    void underLimitAddsHeadersAndRemainingDecreases() {
        String u = user();
        for (int expected = 4; expected >= 2; expected--) {
            get("/v1/orders/1", "X-User-Id", u).expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Limit", "5")
                    .expectHeader().valueEquals("X-RateLimit-Remaining", Integer.toString(expected))
                    .expectHeader().exists("X-RateLimit-Reset");
        }
    }

    @Test
    void resetIsSecondsUntilFull() {
        get("/v1/orders/1", "X-User-Id", user()).expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Reset", "1000"); // ceil(1 / 0.001)
    }

    @Test
    void overLimitIs429WithoutHittingBackend() {
        String u = user();
        for (int i = 0; i < 5; i++) {
            get("/v1/orders/1", "X-User-Id", u).expectStatus().isOk();
        }
        int before = HITS.get();
        get("/v1/orders/1", "X-User-Id", u).expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Content-Type", "application/json")
                .expectHeader().valueEquals("X-RateLimit-Limit", "5")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().exists("X-RateLimit-Reset")
                .expectHeader().value("Retry-After", v -> assertThat(Long.parseLong(v)).isGreaterThanOrEqualTo(1))
                .expectBody().jsonPath("$.error").isEqualTo("rate_limited")
                .jsonPath("$.ruleId").isEqualTo("per-user")
                .jsonPath("$.retryAfterMs").isNumber();
        assertThat(HITS.get()).isEqualTo(before);
    }

    @Test
    void usersHaveIndependentBuckets() {
        String a = user();
        String b = user();
        for (int i = 0; i < 5; i++) {
            get("/v1/orders/1", "X-User-Id", a).expectStatus().isOk();
        }
        get("/v1/orders/1", "X-User-Id", a).expectStatus().isEqualTo(429);
        get("/v1/orders/1", "X-User-Id", b).expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "4");
    }

    @Test
    void missingUserIdFallsBackToAnonBucket() {
        get("/anon/x").expectStatus().isOk();
        get("/anon/x").expectStatus().isOk();
        get("/anon/x").expectStatus().isEqualTo(429);
    }

    @Test
    void apiKeyRule() {
        String k = user();
        get("/apikey/x", "X-Api-Key", k).expectStatus().isOk();
        get("/apikey/x", "X-Api-Key", k).expectStatus().isOk();
        get("/apikey/x", "X-Api-Key", k).expectStatus().isEqualTo(429);
        get("/apikey/x", "X-Api-Key", user()).expectStatus().isOk();
    }

    @Test
    void ipRuleIgnoresForwardedFor() {
        get("/byip/x", "X-Forwarded-For", "1.1.1.1").expectStatus().isOk();
        get("/byip/x", "X-Forwarded-For", "2.2.2.2").expectStatus().isOk();
        get("/byip/x", "X-Forwarded-For", "3.3.3.3").expectStatus().isEqualTo(429);
    }

    @Test
    void globalRule() {
        get("/glob/x", "X-User-Id", user()).expectStatus().isOk();
        get("/glob/x", "X-User-Id", user()).expectStatus().isOk();
        get("/glob/x", "X-User-Id", user()).expectStatus().isEqualTo(429);
    }

    @Test
    void unmatchedPathOrMethodIsNotLimited() {
        for (int i = 0; i < 4; i++) {
            get("/mixed/free").expectStatus().isOk().expectHeader().doesNotExist("X-RateLimit-Limit");
            client.post().uri("/mixed/limited/x").exchange()
                    .expectStatus().isOk().expectHeader().doesNotExist("X-RateLimit-Limit");
        }
        // and a non-routed path is simply a gateway 404, not a rate-limit decision
        get("/nothing-here").expectStatus().isNotFound().expectHeader().doesNotExist("X-RateLimit-Limit");
        // sanity: the GET rule on /mixed/limited/** does apply
        get("/mixed/limited/x").expectStatus().isOk();
        get("/mixed/limited/x").expectStatus().isEqualTo(429);
    }

    @Test
    void secondRuleOnSameRouteCanDeny() {
        // per-user capacity 5, global capacity 3: the 4th request is denied by the global rule
        for (int i = 0; i < 3; i++) {
            get("/multi/x", "X-User-Id", user()).expectStatus().isOk();
        }
        get("/multi/x", "X-User-Id", user()).expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Limit", "3")
                .expectBody().jsonPath("$.ruleId").isEqualTo("global");
    }

    @Test
    void disabledRuleIsIgnored() {
        for (int i = 0; i < 3; i++) {
            get("/disabled/x").expectStatus().isOk().expectHeader().doesNotExist("X-RateLimit-Limit");
        }
    }
}
