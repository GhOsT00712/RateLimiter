package com.ratelimiter.controlplane;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("rules")
public class Rule {
    @Id private String id;
    private String serviceId;
    private String ruleId;
    private Match match;
    private KeyBy keyBy;
    private long capacity;
    private double refillPerSec;
    private OnFailure onFailure;
    private boolean enabled;
    @Version private Long version;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public Match getMatch() { return match; }
    public void setMatch(Match match) { this.match = match; }
    public KeyBy getKeyBy() { return keyBy; }
    public void setKeyBy(KeyBy keyBy) { this.keyBy = keyBy; }
    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }
    public double getRefillPerSec() { return refillPerSec; }
    public void setRefillPerSec(double refillPerSec) { this.refillPerSec = refillPerSec; }
    public OnFailure getOnFailure() { return onFailure; }
    public void setOnFailure(OnFailure onFailure) { this.onFailure = onFailure; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
