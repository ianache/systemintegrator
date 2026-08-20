package com.cl2.integration.integration.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "integration.security.vault")
public class VaultProperties {
    private boolean enabled = false;
    private String uri = "http://localhost:8200";
    private String token = "root";
    private int cacheTtlSeconds = 600;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
}
