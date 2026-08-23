package com.cl2.integration.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "KEYCLOAK_ISSUER_URI=https://issuer.example.test/realms/integration",
                "spring.main.allow-bean-definition-overriding=true"
        })
@ActiveProfiles("qa-e2e")
@Import(GatewaySecurityTest.TestSecurityConfiguration.class)
class GatewaySecurityTest {

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    void rejectsRequestsWithoutBearerToken() {
        webTestClient.get().uri("/api/v1/integration-profiles")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsInvalidBearerToken() {
        webTestClient.get().uri("/api/v1/integration-profiles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-test-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsAuthenticatedJwtWithoutTenantClaim() {
        webTestClient.mutateWith(mockJwt()).get()
                .uri("/api/v1/integration-profiles")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsAuthenticatedJwtWithMalformedTenantClaim() {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.claim("tenant_id", "not-a-uuid"))).get()
                .uri("/api/v1/integration-profiles")
                .exchange()
                .expectStatus().isForbidden();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSecurityConfiguration {

        @Bean
        ReactiveAuthenticationManagerResolver<ServerWebExchange> issuerAuthenticationManagerResolver() {
            return exchange -> Mono.error(new OAuth2AuthenticationException(new OAuth2Error("invalid_token")));
        }
    }
}
