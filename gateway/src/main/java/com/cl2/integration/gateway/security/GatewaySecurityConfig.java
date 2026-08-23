package com.cl2.integration.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

@Configuration
@EnableWebFluxSecurity
@Profile("qa-e2e")
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            ReactiveAuthenticationManagerResolver<ServerWebExchange> issuerAuthenticationManagerResolver) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(issuerAuthenticationManagerResolver))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveAuthenticationManagerResolver<ServerWebExchange> issuerAuthenticationManagerResolver(
            @Value("${keycloak.issuer-uri}") String microserviciosIssuer,
            @Value("${keycloak.apps-issuer-uri}") String appsIssuer) {
        Map<String, ReactiveJwtDecoder> decodersByIssuer = Map.of(
                microserviciosIssuer, ReactiveJwtDecoders.fromIssuerLocation(microserviciosIssuer),
                appsIssuer, ReactiveJwtDecoders.fromIssuerLocation(appsIssuer));
        return TrustedIssuersAuthenticationManagerResolver.from(decodersByIssuer);
    }
}
