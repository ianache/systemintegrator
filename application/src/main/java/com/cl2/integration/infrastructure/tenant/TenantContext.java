package com.cl2.integration.infrastructure.tenant;

import com.cl2.integration.application.exception.TenantRequiredException;
import java.util.Objects;
import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        TENANT_ID.set(Objects.requireNonNull(tenantId, "tenantId must not be null"));
    }

    public static UUID requireTenantId() {
        UUID tenantId = TENANT_ID.get();
        if (tenantId == null) {
            throw new TenantRequiredException();
        }
        return tenantId;
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}
