package com.cl2.integration.integration.resilience;

import java.util.UUID;

public class RateLimitExceededException extends RuntimeException {
    private final UUID tenantId;
    private final String connector;
    private final long retryAfterSeconds;

    public RateLimitExceededException(UUID tenantId, String connector, long retryAfterSeconds) {
        super("Rate limit exceeded for tenant " + tenantId + " on connector " + connector + ". Retry after " + retryAfterSeconds + "s");
        this.tenantId = tenantId;
        this.connector = connector;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public UUID getTenantId() { return tenantId; }
    public String getConnector() { return connector; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
