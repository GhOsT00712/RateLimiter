package com.ratelimiter.controlplane;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** serviceId -> token (local stand-in for real authorization). */
@ConfigurationProperties(prefix = "rl")
public class RlProperties {
    private Map<String, String> serviceTokens = new HashMap<>();

    public Map<String, String> getServiceTokens() { return serviceTokens; }
    public void setServiceTokens(Map<String, String> serviceTokens) { this.serviceTokens = serviceTokens; }
}
