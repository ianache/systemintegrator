package com.cl2.integration.infrastructure.tenant;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void validTenantHeaderIsAvailableToTheRequestChainAndClearedAfterwards() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-ID", tenantId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            assertThat(TenantContext.requireTenantId()).isEqualTo(tenantId));

        assertThatThrownBy(TenantContext::requireTenantId)
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void missingTenantHeaderIsRejectedAtTheHttpBoundary() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The request chain must not run without a tenant header");
        });

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void malformedTenantHeaderIsRejectedAtTheHttpBoundary() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-ID", "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The request chain must not run with a malformed tenant header");
        });

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void rejectedRequestDoesNotRetainATenantFromTheWorkerThread() throws Exception {
        TenantContext.set(UUID.randomUUID());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The request chain must not run without a tenant header");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThatThrownBy(TenantContext::requireTenantId)
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void tenantContextIsClearedWhenTheRequestChainFails() {
        UUID tenantId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-ID", tenantId.toString());

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
            (ignoredRequest, ignoredResponse) -> {
                assertThat(TenantContext.requireTenantId()).isEqualTo(tenantId);
                throw new IllegalStateException("chain failure");
            }))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(TenantContext::requireTenantId)
            .isInstanceOf(RuntimeException.class);
    }
}
