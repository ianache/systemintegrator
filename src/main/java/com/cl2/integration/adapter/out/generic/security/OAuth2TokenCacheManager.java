package com.cl2.integration.adapter.out.generic.security;

import com.cl2.integration.adapter.out.generic.model.AuthConfig;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class OAuth2TokenCacheManager {

    @FunctionalInterface
    public interface TokenFetcher {
        String fetchToken(String tokenUrl, String clientId, String clientSecretRef, String scope);
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}

    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final TokenFetcher tokenFetcher;
    private final Clock clock;

    public OAuth2TokenCacheManager(TokenFetcher tokenFetcher) {
        this(tokenFetcher, Clock.systemUTC());
    }

    public OAuth2TokenCacheManager(TokenFetcher tokenFetcher, Clock clock) {
        this.tokenFetcher = Objects.requireNonNull(tokenFetcher, "TokenFetcher must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public String getAccessToken(String tenantId, AuthConfig authConfig) {
        Objects.requireNonNull(authConfig, "AuthConfig must not be null");
        String effectiveTenant = tenantId != null ? tenantId : "default";
        String cacheKey = effectiveTenant + ":" + authConfig.clientId() + ":" + authConfig.tokenUrl();
        Instant now = Instant.now(clock);

        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(now.plusSeconds(60))) {
            return cached.accessToken();
        }

        return tokenCache.compute(cacheKey, (key, existing) -> {
            Instant currentNow = Instant.now(clock);
            if (existing != null && existing.expiresAt().isAfter(currentNow.plusSeconds(60))) {
                return existing;
            }
            String freshToken = tokenFetcher.fetchToken(
                    authConfig.tokenUrl(),
                    authConfig.clientId(),
                    authConfig.clientSecretRef(),
                    authConfig.scope()
            );
            return new CachedToken(freshToken, currentNow.plusSeconds(3600));
        }).accessToken();
    }
}
