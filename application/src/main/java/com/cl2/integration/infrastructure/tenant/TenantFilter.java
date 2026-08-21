package com.cl2.integration.infrastructure.tenant;

import com.cl2.integration.adapter.in.web.ApiProblemDetailFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private final ObjectMapper objectMapper;

    public TenantFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String tenantHeader = request.getHeader(TENANT_HEADER);
            if (tenantHeader == null) {
                writeProblem(response, request, "TENANT_HEADER_MISSING", "X-Tenant-ID is required");
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantHeader);
            } catch (IllegalArgumentException exception) {
                writeProblem(response, request, "TENANT_HEADER_MALFORMED", "X-Tenant-ID must be a valid UUID");
                return;
            }

            TenantContext.set(tenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void writeProblem(
            HttpServletResponse response,
            HttpServletRequest request,
            String errorCode,
            String detail) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiProblemDetailFactory.create(HttpStatus.BAD_REQUEST, errorCode, detail, request));
    }
}
