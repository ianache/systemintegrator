package com.cl2.integration.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TenantClaimGatewayFilter implements GlobalFilter, Ordered {

    static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .flatMap(this::tenantId)
                .switchIfEmpty(Mono.defer(() -> forbidden(exchange).then(Mono.<UUID>empty())))
                .flatMap(tenantId -> chain.filter(exchange.mutate()
                        .request(request -> request.headers(headers -> {
                            headers.remove(TENANT_HEADER);
                            headers.add(TENANT_HEADER, tenantId.toString());
                        }))
                        .build()))
                .onErrorResume(InvalidTenantClaimException.class, exception -> {
                    return forbidden(exchange);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private Mono<UUID> tenantId(Jwt jwt) {
        return Mono.justOrEmpty(jwt.getClaimAsString("tenant_id"))
                .flatMap(claim -> {
                    try {
                        return Mono.just(UUID.fromString(claim));
                    } catch (IllegalArgumentException exception) {
                        return Mono.error(new InvalidTenantClaimException());
                    }
                });
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return Mono.empty();
    }
}
