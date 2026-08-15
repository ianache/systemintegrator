package com.cl2.integration.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        UUID tenantId = parseTenantId(request.getHeader(TENANT_HEADER));
        if (tenantId == null) {
            TenantContext.clear();
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Tenant-ID must be a valid UUID");
            return;
        }

        TenantContext.set(tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID parseTenantId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(headerValue);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
