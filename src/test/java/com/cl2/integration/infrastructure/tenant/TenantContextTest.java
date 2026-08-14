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
    void requireTenantIdThrowsWhenNoTenantHasBeenSet() {
        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(TenantRequiredException.class);
    }

    @Test
    void requireTenantIdReturnsTheTenantThatWasSet() {
        UUID tenantId = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

        TenantContext.set(tenantId);

        assertThat(TenantContext.requireTenantId()).isEqualTo(tenantId);
    }

    @Test
    void clearRemovesTheActiveTenant() {
        TenantContext.set(UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715"));

        TenantContext.clear();

        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(TenantRequiredException.class);
    }
}
