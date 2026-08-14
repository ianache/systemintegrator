package com.cl2.integration.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String tenantHeader = request.getHeader(TENANT_HEADER);
        if (tenantHeader == null) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Tenant-ID is required");
            return;
        }

        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantHeader);
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Tenant-ID must be a valid UUID");
            return;
        }

        TenantContext.set(tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
