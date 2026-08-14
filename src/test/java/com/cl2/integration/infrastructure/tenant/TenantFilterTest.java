package com.cl2.integration.infrastructure.tenant;

import com.cl2.integration.application.exception.TenantRequiredException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantFilterTest {

    private final TenantFilter tenantFilter = new TenantFilter();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void makesTheTenantHeaderAvailableDuringTheFilterChainAndClearsItAfterward() throws Exception {
        UUID tenantId = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-ID", tenantId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> tenantIdSeenByChain = new AtomicReference<>();

        tenantFilter.doFilter(request, response,
                (servletRequest, servletResponse) -> tenantIdSeenByChain.set(TenantContext.requireTenantId()));

        assertThat(tenantIdSeenByChain.get()).isEqualTo(tenantId);
        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(TenantRequiredException.class);
    }

    @Test
    void rejectsARequestWithoutTheTenantHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean filterChainWasCalled = new AtomicBoolean();

        tenantFilter.doFilter(new MockHttpServletRequest(), response,
                (servletRequest, servletResponse) -> filterChainWasCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(filterChainWasCalled).isFalse();
    }

    @Test
    void rejectsARequestWithAMalformedTenantHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-ID", "invalid-tenant-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean filterChainWasCalled = new AtomicBoolean();

        tenantFilter.doFilter(request, response,
                (servletRequest, servletResponse) -> filterChainWasCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(filterChainWasCalled).isFalse();
    }
}
