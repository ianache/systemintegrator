package com.cl2.integration.adapter.out.generic.security;

import com.cl2.integration.adapter.out.generic.model.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OAuth2TokenCacheManagerTest {

    private AtomicInteger fetchCounter;
    private OAuth2TokenCacheManager.TokenFetcher fetcher;
    private AuthConfig authConfig;

    @BeforeEach
    void setUp() {
        fetchCounter = new AtomicInteger(0);
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
        OAuth2TokenCacheManager cacheManager = new OAuth2TokenCacheManager(fetcher);

        String token1 = cacheManager.getAccessToken("tenant-1", authConfig);
        String token2 = cacheManager.getAccessToken("tenant-1", authConfig);

        assertEquals("token-1", token1);
        assertEquals("token-1", token2);
        assertEquals(1, fetchCounter.get(), "Token fetcher should only be called once when token is cached and valid");
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
