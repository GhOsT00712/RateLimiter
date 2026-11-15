package com.ratelimiter.gateway.sync;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/** Scriptable control plane: serves a snapshot with its ETag, 304s on match, can fail on demand. */
final class StubControlPlane {

    enum Mode { OK, ERROR_500, MALFORMED }

    private final HttpServer server;
    private final List<String> ifNoneMatch = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String etag;
    private volatile String body;
    private volatile Mode mode = Mode.OK;

    StubControlPlane(String etag, String body) {
        this.etag = etag;
        this.body = body;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/rules/snapshot", ex -> {
            requests.incrementAndGet();
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            ifNoneMatch.add(inm == null ? "<none>" : inm);
            byte[] out = new byte[0];
            int status = 200;
            if (mode == Mode.ERROR_500) {
                status = 500;
            } else if (mode == Mode.MALFORMED) {
                out = "{not json".getBytes(StandardCharsets.UTF_8);
            } else if (("\"" + this.etag + "\"").equals(inm)) {
                status = 304;
            } else {
                ex.getResponseHeaders().add("ETag", "\"" + this.etag + "\"");
                ex.getResponseHeaders().add("Content-Type", "application/json");
                out = this.body.getBytes(StandardCharsets.UTF_8);
            }
            if (out.length == 0) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
            }
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void serve(String etag, String body) {
        this.etag = etag;
        this.body = body;
    }

    void mode(Mode mode) {
        this.mode = mode;
    }

    List<String> ifNoneMatch() {
        return ifNoneMatch;
    }

    int requests() {
        return requests.get();
    }

    void stop() {
        server.stop(0);
    }

    static String rule(String service, String id, String path, int capacity) {
        return "{\"serviceId\":\"" + service + "\",\"ruleId\":\"" + id + "\",\"match\":{\"method\":\"*\",\"path\":\""
                + path + "\"},\"keyBy\":\"global\",\"capacity\":" + capacity
                + ",\"refillPerSec\":0.001,\"onFailure\":\"open\",\"enabled\":true,\"version\":1,"
                + "\"updatedAt\":\"2026-11-15T08:46:10.696Z\"}";
    }

    static String snapshot(String etag, String... rules) {
        return "{\"etag\":\"" + etag + "\",\"rules\":[" + String.join(",", rules) + "]}";
    }
}
