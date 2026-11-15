package com.ratelimiter.controlplane;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1")
public class RuleController {

    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private final RuleService service;
    private final RlProperties props;

    public RuleController(RuleService service, RlProperties props) {
        this.service = service;
        this.props = props;
    }

    @PutMapping("/services/{serviceId}/rules/{ruleId}")
    public ResponseEntity<RuleResponse> put(
            @PathVariable String serviceId, @PathVariable String ruleId,
            @RequestHeader(value = "X-Service-Token", required = false) String token,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @Valid @RequestBody RuleRequest body) {
        checkIds(serviceId, ruleId);
        authorize(serviceId, token);
        RuleService.Saved saved = service.put(serviceId, ruleId, body, ifMatch, ifNoneMatch);
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag("\"" + saved.rule().getVersion() + "\"")
                .body(RuleResponse.from(saved.rule()));
    }

    @GetMapping("/services/{serviceId}/rules")
    public List<RuleResponse> list(@PathVariable String serviceId) {
        return service.list(serviceId).stream().map(RuleResponse::from).toList();
    }

    @DeleteMapping("/services/{serviceId}/rules/{ruleId}")
    public ResponseEntity<Void> delete(
            @PathVariable String serviceId, @PathVariable String ruleId,
            @RequestHeader(value = "X-Service-Token", required = false) String token) {
        checkIds(serviceId, ruleId);
        authorize(serviceId, token);
        service.delete(serviceId, ruleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rules/snapshot")
    public ResponseEntity<?> snapshot(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<Rule> rules = service.all();
        String etag = RuleService.etag(rules);
        String quoted = "\"" + etag + "\"";
        if (ifNoneMatch != null && ifNoneMatch.trim().replace("\"", "").equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(quoted).build();
        }
        return ResponseEntity.ok().eTag(quoted)
                .body(Map.of("etag", etag, "rules", rules.stream().map(RuleResponse::from).toList()));
    }

    private static void checkIds(String serviceId, String ruleId) {
        if (!serviceId.matches(ID_PATTERN) || !ruleId.matches(ID_PATTERN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid serviceId or ruleId");
        }
    }

    private void authorize(String serviceId, String token) {
        String expected = props.getServiceTokens().get(serviceId);
        if (expected == null || token == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }
}
