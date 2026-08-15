package com.cl2.integration.infrastructure.tenant;

import com.cl2.integration.application.exception.TenantRequiredException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void requireTenantIdRejectsOperationsWithoutAnActiveTenant() {
        assertThatThrownBy(TenantContext::requireTenantId)
            .isInstanceOf(TenantRequiredException.class);
    }

    @Test
    void setMakesTheTenantAvailableOnlyUntilItIsCleared() {
        UUID tenantId = UUID.randomUUID();

        TenantContext.set(tenantId);

        assertThat(TenantContext.requireTenantId()).isEqualTo(tenantId);

        TenantContext.clear();

        assertThatThrownBy(TenantContext::requireTenantId)
            .isInstanceOf(TenantRequiredException.class);
    }
}
