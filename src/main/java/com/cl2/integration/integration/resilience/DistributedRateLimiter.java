package com.cl2.integration.integration.resilience;

import java.util.UUID;

public interface DistributedRateLimiter {
    RateLimitResult tryAcquire(UUID tenantId, String connector, int requestsPerUnit, String unit);
    void checkPermission(UUID tenantId, String connector, int requestsPerUnit, String unit);
}
