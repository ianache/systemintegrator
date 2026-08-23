package com.cl2.integration.gateway.security;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

final class TrustedIssuersAuthenticationManagerResolver {

    private TrustedIssuersAuthenticationManagerResolver() {
    }

    static JwtIssuerReactiveAuthenticationManagerResolver from(Map<String, ReactiveJwtDecoder> decodersByIssuer) {
        Map<String, ReactiveAuthenticationManager> managersByIssuer = decodersByIssuer.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new JwtReactiveAuthenticationManager(entry.getValue())));

        return new JwtIssuerReactiveAuthenticationManagerResolver(issuer ->
                Mono.justOrEmpty(managersByIssuer.get(issuer))
        );
    }
}
