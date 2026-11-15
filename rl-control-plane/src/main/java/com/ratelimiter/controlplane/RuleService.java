package com.ratelimiter.controlplane;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuleService {

    public record Saved(Rule rule, boolean created) {}

    private final RuleRepository repo;

    public RuleService(RuleRepository repo) {
        this.repo = repo;
    }

    /** ifMatch / ifNoneMatch are the raw header values (null when absent). */
    public Saved put(String serviceId, String ruleId, RuleRequest req, String ifMatch, String ifNoneMatch) {
        if (ifMatch != null && ifNoneMatch != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match and If-None-Match are exclusive");
        }
        if (ifNoneMatch != null && !ifNoneMatch.trim().equals("*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-None-Match must be *");
        }
        Long expected = ifMatch == null ? null : parseVersion(ifMatch);
        boolean conditional = ifMatch != null || ifNoneMatch != null;

        String id = serviceId + ":" + ruleId;
        Rule rule = repo.findById(id).orElse(null);
        if (ifNoneMatch != null && rule != null) {
            throw precondition();
        }
        if (expected != null && (rule == null || !expected.equals(rule.getVersion()))) {
            throw precondition();
        }
        boolean created = rule == null;
        if (created) {
            rule = new Rule();
            rule.setId(id);
            rule.setServiceId(serviceId);
            rule.setRuleId(ruleId);
        }
        rule.setMatch(new Match(req.match().method(), req.match().path()));
        rule.setKeyBy(req.keyBy());
        rule.setCapacity(req.capacity());
        rule.setRefillPerSec(req.refillPerSec());
        rule.setOnFailure(req.onFailure() == null ? OnFailure.LOCAL_FALLBACK : req.onFailure());
        rule.setEnabled(req.enabled() == null || req.enabled());
        rule.setUpdatedAt(Instant.now());
        try {
            return new Saved(repo.save(rule), created);
        } catch (OptimisticLockingFailureException | DuplicateKeyException e) {
            throw conditional ? precondition() : new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent write");
        }
    }

    public List<Rule> list(String serviceId) {
        return repo.findByServiceId(serviceId);
    }

    public void delete(String serviceId, String ruleId) {
        String id = serviceId + ":" + ruleId;
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
        }
        repo.deleteById(id);
    }

    public List<Rule> all() {
        return repo.findAll(Sort.by("id"));
    }

    public static String etag(List<Rule> sortedRules) {
        StringBuilder sb = new StringBuilder();
        for (Rule r : sortedRules) {
            sb.append(r.getId()).append(':').append(r.getVersion()).append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Long parseVersion(String header) {
        try {
            return Long.parseLong(header.trim().replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must be a numeric version");
        }
    }

    private static ResponseStatusException precondition() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Precondition failed");
    }
}
