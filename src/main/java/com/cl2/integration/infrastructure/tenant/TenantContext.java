package com.cl2.integration.infrastructure.tenant;

import com.cl2.integration.application.exception.TenantRequiredException;
import java.util.Objects;
import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT_TENANT.set(Objects.requireNonNull(tenantId, "tenantId is required"));
    }

    public static UUID requireTenantId() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new TenantRequiredException();
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
