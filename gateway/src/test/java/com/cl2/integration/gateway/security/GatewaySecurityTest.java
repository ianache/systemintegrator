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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
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
        ReactiveJwtDecoder jwtDecoder() {
            return token -> Mono.error(new BadJwtException("invalid test token"));
        }
    }
}
