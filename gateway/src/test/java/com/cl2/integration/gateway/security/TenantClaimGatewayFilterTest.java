package com.cl2.integration.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication;

class TenantClaimGatewayFilterTest {

    @Test
    void replacesAllClientTenantHeadersWithTheAuthenticatedTenantId() {
        UUID tenantId = UUID.fromString("24a4a27e-98ff-4d55-882e-4b3741e4dd3e");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/integration-profiles")
                .header("X-Tenant-ID", "client-tenant-one")
                .header("X-Tenant-ID", "client-tenant-two")
                .build());
        AtomicReference<List<String>> forwardedTenantHeaders = new AtomicReference<>();
        GatewayFilterChain downstream = forwarded -> {
            forwardedTenantHeaders.set(forwarded.getRequest().getHeaders().get("X-Tenant-ID"));
            forwarded.getResponse().setStatusCode(OK);
            return Mono.empty();
        };

        new TenantClaimGatewayFilter().filter(exchange, downstream)
                .contextWrite(withAuthentication(new JwtAuthenticationToken(jwtWithTenant(tenantId))))
                .block();

        assertEquals(List.of(tenantId.toString()), forwardedTenantHeaders.get());
        assertEquals(OK, exchange.getResponse().getStatusCode());
    }

    private Jwt jwtWithTenant(UUID tenantId) {
        return new Jwt(
                "test-token",
                Instant.parse("2026-08-15T00:00:00Z"),
                Instant.parse("2026-08-15T01:00:00Z"),
                java.util.Map.of("alg", "none"),
                java.util.Map.of("sub", "test-user", "tenant_id", tenantId.toString()));
    }
}
