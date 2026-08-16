package com.cl2.integration.integration.resilience;

import java.util.UUID;

public class CircuitBreakerOpenException extends RuntimeException {
    private final UUID tenantId;
    private final String connector;

    public CircuitBreakerOpenException(UUID tenantId, String connector) {
        super("Circuit breaker is OPEN for tenant " + tenantId + " and connector " + connector);
        this.tenantId = tenantId;
        this.connector = connector;
    }

    public UUID getTenantId() { return tenantId; }
    public String getConnector() { return connector; }
}
