package com.cl2.integration.adapter.out.generic.security;

import com.cl2.integration.adapter.out.generic.model.AuthConfig;
import com.cl2.integration.infrastructure.metrics.IntegrationMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuth2TokenCacheManagerTest {

    private AtomicInteger fetchCounter;
    private OAuth2TokenCacheManager.TokenFetcher fetcher;
    private AuthConfig authConfig;
    private IntegrationMetrics metrics;

    @BeforeEach
    void setUp() {
        fetchCounter = new AtomicInteger(0);
        metrics = mock(IntegrationMetrics.class);
        fetcher = (tokenUrl, clientId, clientSecretRef, scope) -> 
            "token-" + fetchCounter.incrementAndGet();
        authConfig = new AuthConfig(
            "OAUTH2_CLIENT_CREDENTIALS",
            "https://oauth.example.com/token",
            "client-123",
            "secret-ref",
            "read:all",
            null, null, null, null
        );
    }

    @Test
    void shouldCacheAndReturnTokenOnSubsequentRequests() {
        OAuth2TokenCacheManager cacheManager = new OAuth2TokenCacheManager(fetcher, Clock.systemUTC(), metrics);

        String token1 = cacheManager.getAccessToken("tenant-1", authConfig);
        String token2 = cacheManager.getAccessToken("tenant-1", authConfig);

        assertEquals("token-1", token1);
        assertEquals("token-1", token2);
        assertEquals(1, fetchCounter.get(), "Token fetcher should only be called once when token is cached and valid");

        verify(metrics, times(1)).recordOAuth2TokenRequest("tenant-1", "client-123", "SUCCESS");
        verify(metrics, times(1)).recordOAuth2TokenCacheHit("tenant-1", "client-123");
    }

    @Test
    void shouldRefreshTokenWhenTokenIsNearExpiration() {
        Instant baseTime = Instant.parse("2026-08-17T12:00:00Z");
        MutableClock clock = new MutableClock(baseTime, ZoneId.of("UTC"));

        OAuth2TokenCacheManager cacheManager = new OAuth2TokenCacheManager(fetcher, clock);

        String token1 = cacheManager.getAccessToken("tenant-1", authConfig);
        assertEquals("token-1", token1);
        assertEquals(1, fetchCounter.get());

        // Advance time by 3545 seconds (55 seconds left until 3600s expiration, which is < 60s)
        clock.advanceBy(Duration.ofSeconds(3545));

        String token2 = cacheManager.getAccessToken("tenant-1", authConfig);
        assertEquals("token-2", token2);
        assertEquals(2, fetchCounter.get(), "Token fetcher should be called again when token has <60s left before expiration");
    }

    @Test
    void shouldSupportOverloadedGetAccessTokenWithExplicitCredentials() {
        OAuth2TokenCacheManager cacheManager = new OAuth2TokenCacheManager(fetcher);

        String token1 = cacheManager.getAccessToken("tenant-1", "https://oauth.example.com/token", "client-123", "secret-xyz", "read:all");
        String token2 = cacheManager.getAccessToken("tenant-1", "https://oauth.example.com/token", "client-123", "secret-xyz", "read:all");

        assertEquals("token-1", token1);
        assertEquals("token-1", token2);
        assertEquals(1, fetchCounter.get());
    }

    @Test
    void shouldFetchTokenFromKeycloakEndpointUsingDefaultFetcher() {
        com.github.tomakehurst.wiremock.WireMockServer wireMock = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort()
        );
        wireMock.start();

        try {
            wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/realms/cl2/protocol/openid-connect/token")
                    .withHeader("Content-Type", com.github.tomakehurst.wiremock.client.WireMock.containing("application/x-www-form-urlencoded"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("grant_type=client_credentials"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("client_id=keycloak-client"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("client_secret=keycloak-secret"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("scope=openid"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withStatus(200)
                            .withBody("{\"access_token\": \"mock-keycloak-jwt-123\", \"expires_in\": 300, \"token_type\": \"Bearer\"}")));

            OAuth2TokenCacheManager cacheManager = new OAuth2TokenCacheManager();
            String tokenUrl = wireMock.baseUrl() + "/realms/cl2/protocol/openid-connect/token";

            String token = cacheManager.getAccessToken("tenant-kc", tokenUrl, "keycloak-client", "keycloak-secret", "openid");

            assertEquals("mock-keycloak-jwt-123", token);

            wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/realms/cl2/protocol/openid-connect/token")
            ));
        } finally {
            wireMock.stop();
        }
    }

    private static class MutableClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone;

        MutableClock(Instant initialInstant, ZoneId zone) {
            this.currentInstant = initialInstant;
            this.zone = zone;
        }

        void advanceBy(Duration duration) {
            this.currentInstant = this.currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
