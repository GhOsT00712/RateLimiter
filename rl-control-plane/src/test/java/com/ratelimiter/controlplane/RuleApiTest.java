package com.ratelimiter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@SpringBootTest(properties = {"rl.service-tokens.orders=tok-orders", "rl.service-tokens.billing=tok-billing"})
@Testcontainers
class RuleApiTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    static final String BODY = """
            {"match":{"method":"GET","path":"/x"},"keyBy":"userId","capacity":10,"refillPerSec":2.5}""";

    @Autowired WebApplicationContext context;
    @Autowired RuleRepository repo;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private ResultActions send(String service, String rule, String token, String body, String... headers) throws Exception {
        MockHttpServletRequestBuilder r = put("/v1/services/{s}/rules/{r}", service, rule)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) r.header("X-Service-Token", token);
        for (int i = 0; i < headers.length; i += 2) r.header(headers[i], headers[i + 1]);
        return mvc.perform(r);
    }

    private ResultActions putOrders(String rule, String... headers) throws Exception {
        return send("orders", rule, "tok-orders", BODY, headers);
    }

    @Test
    void createOnlyThenConflict() throws Exception {
        putOrders("r1", "If-None-Match", "*")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.onFailure").value("local_fallback"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.keyBy").value("userId"));
        putOrders("r1", "If-None-Match", "*").andExpect(status().isPreconditionFailed());
    }

    @Test
    void updateWithIfMatch() throws Exception {
        putOrders("r1").andExpect(status().isCreated());
        putOrders("r1", "If-Match", "\"0\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.version").value(1));
        putOrders("r1", "If-Match", "0").andExpect(status().isPreconditionFailed());
        putOrders("missing", "If-Match", "0").andExpect(status().isPreconditionFailed());
        putOrders("r1", "If-Match", "abc").andExpect(status().isBadRequest());
    }

    @Test
    void plainUpsert() throws Exception {
        putOrders("r1").andExpect(status().isCreated());
        putOrders("r1").andExpect(status().isOk());
    }

    @Test
    void authorization() throws Exception {
        send("orders", "r1", "wrong", BODY).andExpect(status().isForbidden());
        send("orders", "r1", null, BODY).andExpect(status().isForbidden());
        send("orders", "r1", "tok-billing", BODY).andExpect(status().isForbidden());
        send("unknown", "r1", "tok-orders", BODY).andExpect(status().isForbidden());
        mvc.perform(delete("/v1/services/orders/rules/r1")).andExpect(status().isForbidden());
    }

    @Test
    void invalidBodies() throws Exception {
        send("orders", "r1", "tok-orders", BODY.replace("\"capacity\":10", "\"capacity\":0"))
                .andExpect(status().isBadRequest());
        send("orders", "r1", "tok-orders", BODY.replace("userId", "nobody")).andExpect(status().isBadRequest());
        send("orders", "r1", "tok-orders", BODY.replace("\"/x\"", "\"x\"")).andExpect(status().isBadRequest());
        send("orders", "r1", "tok-orders", BODY.replace("GET", "FETCH")).andExpect(status().isBadRequest());
        send("orders", "r1", "tok-orders", BODY.replace("2.5", "0")).andExpect(status().isBadRequest());
        send("orders", "bad id!", "tok-orders", BODY).andExpect(status().isBadRequest());
        putOrders("r1", "If-Match", "0", "If-None-Match", "*").andExpect(status().isBadRequest());
    }

    @Test
    void listIsPerService() throws Exception {
        putOrders("a").andExpect(status().isCreated());
        putOrders("b").andExpect(status().isCreated());
        send("billing", "c", "tok-billing", BODY).andExpect(status().isCreated());
        mvc.perform(get("/v1/services/orders/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].serviceId").value("orders"))
                .andExpect(jsonPath("$[1].serviceId").value("orders"));
    }

    @Test
    void deleteThenNotFound() throws Exception {
        putOrders("r1").andExpect(status().isCreated());
        mvc.perform(delete("/v1/services/orders/rules/r1").header("X-Service-Token", "tok-orders"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/v1/services/orders/rules/r1").header("X-Service-Token", "tok-orders"))
                .andExpect(status().isNotFound());
    }

    private String snapshotEtag() throws Exception {
        return mvc.perform(get("/v1/rules/snapshot")).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
    }

    private void assertChanged(String oldEtag) throws Exception {
        mvc.perform(get("/v1/rules/snapshot").header("If-None-Match", oldEtag)).andExpect(status().isOk());
        assertThat(snapshotEtag()).isNotEqualTo(oldEtag);
    }

    @Test
    void snapshotEtagTracksChanges() throws Exception {
        String empty = snapshotEtag();
        mvc.perform(get("/v1/rules/snapshot").header("If-None-Match", empty))
                .andExpect(status().isNotModified());

        putOrders("r1").andExpect(status().isCreated());
        assertChanged(empty);
        String afterCreate = snapshotEtag();
        mvc.perform(get("/v1/rules/snapshot").header("If-None-Match", afterCreate))
                .andExpect(status().isNotModified());
        mvc.perform(get("/v1/rules/snapshot"))
                .andExpect(jsonPath("$.etag").value(afterCreate.replace("\"", "")))
                .andExpect(jsonPath("$.rules.length()").value(1));

        putOrders("r1").andExpect(status().isOk());
        assertChanged(afterCreate);
        String afterUpdate = snapshotEtag();

        mvc.perform(delete("/v1/services/orders/rules/r1").header("X-Service-Token", "tok-orders"))
                .andExpect(status().isNoContent());
        assertChanged(afterUpdate);
    }
}
