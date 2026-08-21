package com.cl2.integration.adapter.out.generic.security;

import com.cl2.integration.adapter.out.generic.model.AuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OAuth2TokenCacheManager {

    @FunctionalInterface
    public interface TokenFetcher {
        String fetchToken(String tokenUrl, String clientId, String clientSecretRef, String scope);
    }

    public static class DefaultRestClientTokenFetcher implements TokenFetcher {
        private static final Logger log = LoggerFactory.getLogger(DefaultRestClientTokenFetcher.class);
        private final RestClient restClient;

        public DefaultRestClientTokenFetcher(RestClient restClient) {
            this.restClient = Objects.requireNonNull(restClient, "RestClient must not be null");
        }

        @Override
        public String fetchToken(String tokenUrl, String clientId, String clientSecretRef, String scope) {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "client_credentials");
            if (clientId != null && !clientId.isBlank()) {
                formData.add("client_id", clientId);
            }
            if (clientSecretRef != null && !clientSecretRef.isBlank()) {
                formData.add("client_secret", clientSecretRef);
            }
            if (scope != null && !scope.isBlank()) {
                formData.add("scope", scope);
            }

            if (log.isDebugEnabled()) {
                log.debug("OAuth2 Token Request -> POST {} | grant_type=client_credentials, client_id={}, client_secret={}, scope={}",
                        tokenUrl,
                        clientId,
                        com.cl2.integration.adapter.out.http.SensitiveDataRedactor.redact(clientSecretRef),
                        scope);
            }

            try {
                Map<String, Object> response = restClient.post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(formData)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});

                if (response != null && response.get("access_token") != null) {
                    String accessToken = String.valueOf(response.get("access_token"));
                    if (log.isDebugEnabled()) {
                        log.debug("OAuth2 Token Response <- 200 OK from {} | access_token={}, token_type={}, expires_in={}",
                                tokenUrl,
                                com.cl2.integration.adapter.out.http.SensitiveDataRedactor.redact(accessToken),
                                response.get("token_type"),
                                response.get("expires_in"));
                    }
                    return accessToken;
                }
                throw new IllegalStateException("No access_token found in token response from " + tokenUrl);
            } catch (Exception ex) {
                if (log.isDebugEnabled()) {
                    log.debug("OAuth2 Token Request FAILED for URL {}: {}", tokenUrl, ex.getMessage());
                }
                throw ex;
            }
        }
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}

    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final TokenFetcher tokenFetcher;
    private final Clock clock;

    public OAuth2TokenCacheManager() {
        this(new DefaultRestClientTokenFetcher(RestClient.create()), Clock.systemUTC());
    }

    @Autowired(required = false)
    public OAuth2TokenCacheManager(RestClient.Builder restClientBuilder) {
        this(new DefaultRestClientTokenFetcher(
                restClientBuilder != null ? restClientBuilder.build() : RestClient.create()
        ), Clock.systemUTC());
    }

    public OAuth2TokenCacheManager(TokenFetcher tokenFetcher) {
        this(tokenFetcher, Clock.systemUTC());
    }

    public OAuth2TokenCacheManager(TokenFetcher tokenFetcher, Clock clock) {
        this.tokenFetcher = Objects.requireNonNull(tokenFetcher, "TokenFetcher must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public String getAccessToken(String tenantId, AuthConfig authConfig) {
        Objects.requireNonNull(authConfig, "AuthConfig must not be null");
        return getAccessToken(
                tenantId,
                authConfig.tokenUrl(),
                authConfig.clientId(),
                authConfig.clientSecretRef(),
                authConfig.scope()
        );
    }

    public String getAccessToken(String tenantId, String tokenUrl, String clientId, String clientSecret, String scope) {
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw new IllegalArgumentException("Token URL cannot be null or blank");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Client ID cannot be null or blank");
        }
        String effectiveTenant = tenantId != null ? tenantId : "default";
        String cacheKey = effectiveTenant + ":" + clientId + ":" + tokenUrl;
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
            String freshToken = tokenFetcher.fetchToken(tokenUrl, clientId, clientSecret, scope);
            return new CachedToken(freshToken, currentNow.plusSeconds(3600));
        }).accessToken();
    }
}
