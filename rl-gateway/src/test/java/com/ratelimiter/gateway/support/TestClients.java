package com.ratelimiter.gateway.support;

import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;

public final class TestClients {

    private TestClients() {
    }

    /**
     * A WebTestClient that always drains the response body into heap memory, so tests that only check status or
     * headers do not leak the pooled Netty buffer of an unconsumed body.
     */
    public static WebTestClient bindToServer(int port) {
        ExchangeFilterFunction drain = (request, next) -> next.exchange(request).flatMap(response ->
                response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                        .map(bytes -> response.mutate()
                                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))).build()));
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).filter(drain).build();
    }
}
