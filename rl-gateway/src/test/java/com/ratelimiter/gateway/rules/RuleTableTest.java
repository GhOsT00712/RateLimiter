package com.ratelimiter.gateway.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpMethod;

class RuleTableTest {

    private static Rule rule(String service, String id, String method, String path, long cap, double refill,
                             boolean enabled) {
        return new Rule(service, id, new Rule.Match(method, path), Rule.KeyBy.GLOBAL, cap, refill,
                Rule.OnFailure.OPEN, enabled, null);
    }

    private static RuleTable table(Rule... rules) {
        RuleTable t = new RuleTable(new DefaultResourceLoader(), "classpath:does-not-exist.json");
        t.replace(new RuleSnapshot("e", List.of(rules)));
        return t;
    }

    private static List<String> ids(List<Rule> rules) {
        return rules.stream().map(Rule::ruleId).toList();
    }

    @Test
    void wildcardMethodAndPath() {
        RuleTable t = table(rule("s", "any", "*", "*", 1, 1, true));
        assertThat(ids(t.matching("s", HttpMethod.DELETE, "/whatever/at/all"))).containsExactly("any");
        assertThat(t.matching("other", HttpMethod.GET, "/x")).isEmpty();
    }

    @Test
    void methodIsExactAndCaseInsensitive() {
        RuleTable t = table(rule("s", "get", "get", "*", 1, 1, true));
        assertThat(t.matching("s", HttpMethod.GET, "/x")).hasSize(1);
        assertThat(t.matching("s", HttpMethod.POST, "/x")).isEmpty();
    }

    @Test
    void pathPatterns() {
        RuleTable t = table(
                rule("s", "multi", "*", "/v1/orders/**", 1, 1, true),
                rule("s", "single", "*", "/v1/items/{id}", 2, 1, true));
        assertThat(ids(t.matching("s", HttpMethod.GET, "/v1/orders/1/lines"))).containsExactly("multi");
        assertThat(ids(t.matching("s", HttpMethod.GET, "/v1/items/9"))).containsExactly("single");
        assertThat(t.matching("s", HttpMethod.GET, "/v1/items/9/extra")).isEmpty();
        assertThat(t.matching("s", HttpMethod.GET, "/v2/orders/1")).isEmpty();
    }

    @Test
    void mostRestrictiveFirst() {
        RuleTable t = table(
                rule("s", "big", "*", "*", 100, 1, true),
                rule("s", "small-fast", "*", "*", 3, 5, true),
                rule("s", "small-slow", "*", "*", 3, 0.5, true));
        assertThat(ids(t.matching("s", HttpMethod.GET, "/x"))).containsExactly("small-slow", "small-fast", "big");
    }

    @Test
    void disabledRulesAreSkipped() {
        RuleTable t = table(rule("s", "off", "*", "*", 1, 1, false));
        assertThat(t.matching("s", HttpMethod.GET, "/x")).isEmpty();
    }

    @Test
    void replaceSwapsRules() {
        RuleTable t = table(rule("s", "old", "*", "*", 1, 1, true));
        assertThat(ids(t.matching("s", HttpMethod.GET, "/x"))).containsExactly("old");
        t.replace(new RuleSnapshot("e2", List.of(rule("s", "new", "*", "*", 1, 1, true))));
        assertThat(ids(t.matching("s", HttpMethod.GET, "/x"))).containsExactly("new");
        assertThat(t.etag()).isEqualTo("e2");
    }

    @Test
    void loadsJsonIgnoringUnknownPropertiesAndMissingFileIsEmpty() {
        RuleTable loaded = new RuleTable(new DefaultResourceLoader(), "classpath:test-rules.json");
        Rule r = loaded.matching("orders", HttpMethod.GET, "/v1/orders/1").get(0);
        assertThat(r.keyBy()).isEqualTo(Rule.KeyBy.USER_ID);
        assertThat(r.onFailure()).isEqualTo(Rule.OnFailure.LOCAL_FALLBACK);
        assertThat(r.version()).isZero();
        assertThat(loaded.etag()).isEqualTo("test");

        RuleTable missing = new RuleTable(new DefaultResourceLoader(), "file:./nope/rules.json");
        assertThat(missing.rules()).isEmpty();
    }
}
