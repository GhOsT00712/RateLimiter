package com.ratelimiter.gateway.filter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import com.ratelimiter.gateway.limiter.BucketKey;
import com.ratelimiter.gateway.limiter.Decision;
import com.ratelimiter.gateway.limiter.TokenBucketClient;
import com.ratelimiter.gateway.rules.Rule;
import com.ratelimiter.gateway.rules.RuleTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private record Hit(Rule rule, Decision decision) {
    }

    private final RuleTable rules;
    private final TokenBucketClient client;

    public RateLimitFilter(RuleTable rules, TokenBucketClient client) {
        this.rules = rules;
        this.client = client;
    }

    @Override
    public int getOrder() {
        return 0; // well before the routing filters (RouteToRequestUrlFilter, NettyRoutingFilter)
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        var request = exchange.getRequest();
        String serviceId = route.getId();
        List<Rule> matching = rules.matching(serviceId, request.getMethod(), request.getPath().value());
        if (matching.isEmpty()) {
            return chain.filter(exchange);
        }
        String remoteIp = remoteIp(exchange);

        // Sequential; stops at the first denial. Tokens spent on earlier rules are not returned.
        return Flux.fromIterable(matching)
                .concatMap(r -> client.acquire(BucketKey.of(serviceId, r.ruleId(), identity(r, exchange, remoteIp)),
                        r.capacity(), r.refillPerSec(), 1).map(d -> new Hit(r, d)), 1)
                .takeUntil(h -> !h.decision().allowed())
                .collectList()
                // Fail open for now; honouring each rule's onFailure is a later phase.
                .onErrorResume(e -> {
                    log.warn("Rate limit check failed, letting request through: {}", e.toString());
                    return Mono.just(List.of());
                })
                .flatMap(hits -> {
                    if (hits.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    Hit last = hits.get(hits.size() - 1);
                    if (!last.decision().allowed()) {
                        return deny(exchange, last);
                    }
                    Hit tightest = hits.stream()
                            .min(Comparator.comparingLong((Hit h) -> h.decision().remaining())).get();
                    exchange.getResponse().beforeCommit(() -> {
                        addHeaders(exchange.getResponse().getHeaders(), tightest);
                        return Mono.empty();
                    });
                    return chain.filter(exchange);
                });
    }

    private static void addHeaders(HttpHeaders headers, Hit hit) {
        Rule r = hit.rule();
        long remaining = hit.decision().remaining();
        long reset = (long) Math.ceil((r.capacity() - remaining) / r.refillPerSec());
        headers.set("X-RateLimit-Limit", Long.toString(r.capacity()));
        headers.set("X-RateLimit-Remaining", Long.toString(remaining));
        headers.set("X-RateLimit-Reset", Long.toString(reset));
    }

    private static Mono<Void> deny(ServerWebExchange exchange, Hit hit) {
        long retryMs = hit.decision().retryAfterMs();
        long seconds = Math.max(1, (long) Math.ceil(retryMs / 1000.0));
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        HttpHeaders h = response.getHeaders();
        h.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        h.set("X-RateLimit-Limit", Long.toString(hit.rule().capacity()));
        h.set("X-RateLimit-Remaining", "0");
        h.set("X-RateLimit-Reset", Long.toString(seconds));
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":\"rate_limited\",\"ruleId\":\"" + escape(hit.rule().ruleId())
                + "\",\"retryAfterMs\":" + retryMs + "}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String identity(Rule rule, ServerWebExchange exchange, String remoteIp) {
        return switch (rule.keyBy()) {
            case USER_ID -> headerOrAnon(exchange, "X-User-Id", remoteIp);
            case API_KEY -> headerOrAnon(exchange, "X-Api-Key", remoteIp);
            case IP -> remoteIp;
            case GLOBAL -> "global";
        };
    }

    private static String headerOrAnon(ServerWebExchange exchange, String name, String remoteIp) {
        String v = exchange.getRequest().getHeaders().getFirst(name);
        return v == null || v.isBlank() ? "anon:" + remoteIp : v;
    }

    // The connection's address only; X-Forwarded-For is deliberately not trusted.
    private static String remoteIp(ServerWebExchange exchange) {
        InetSocketAddress a = exchange.getRequest().getRemoteAddress();
        return a == null || a.getAddress() == null ? "unknown" : a.getAddress().getHostAddress();
    }
}
