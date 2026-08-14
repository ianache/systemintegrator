package com.cl2.integration.infrastructure.tenant;

import com.cl2.integration.application.exception.TenantRequiredException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TenantFilterTest.TestControllerConfiguration.class)
class TenantFilterTest {

    private final TenantFilter tenantFilter = new TenantFilter(new ObjectMapper());

    @Autowired
    private MockMvc mockMvc;

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
    void clearsAPreSeededTenantWhenTheTenantHeaderIsMissing() throws Exception {
        TenantContext.set(UUID.fromString("b129386f-2ec1-4f2a-8d09-f2aed3b154c2"));

        tenantFilter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                });

        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(TenantRequiredException.class);
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

    @Test
    void clearsAPreSeededTenantWhenTheTenantHeaderIsMalformed() throws Exception {
        TenantContext.set(UUID.fromString("b129386f-2ec1-4f2a-8d09-f2aed3b154c2"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-ID", "invalid-tenant-id");

        tenantFilter.doFilter(request, new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                });

        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(TenantRequiredException.class);
    }

    @Test
    void registeredFilterRejectsARequestBeforeTheControllerCanRespond() throws Exception {
        mockMvc.perform(get("/tenant-filter-test"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registeredFilterAllowsTheControllerToRespondWithAValidTenantHeader() throws Exception {
        mockMvc.perform(get("/tenant-filter-test")
                        .header("X-Tenant-ID", "71923e5e-a4cb-4956-91fd-a492fcab5715"))
                .andExpect(status().isOk())
                .andExpect(content().string("controller reached"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestControllerConfiguration {

        @Bean
        TenantBoundaryController tenantBoundaryController() {
            return new TenantBoundaryController();
        }
    }

    @RestController
    static class TenantBoundaryController {

        @GetMapping("/tenant-filter-test")
        String responseWhenReached() {
            return "controller reached";
        }
    }
}
