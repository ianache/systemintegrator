package com.cl2.integration.integration.security;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Primary
public class VaultSecretResolver implements SecretResolver {
    private final VaultProperties vaultProperties;
    private final InMemorySecretResolver inMemoryResolver;
    private final RestClient restClient;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();

    public VaultSecretResolver(VaultProperties vaultProperties, InMemorySecretResolver inMemoryResolver) {
        this(vaultProperties, inMemoryResolver, RestClient.builder());
    }

    @Autowired
    public VaultSecretResolver(VaultProperties vaultProperties,
                               InMemorySecretResolver inMemoryResolver,
                               RestClient.Builder restClientBuilder) {
        this.vaultProperties = vaultProperties;
        this.inMemoryResolver = inMemoryResolver;
        this.restClient = restClientBuilder.baseUrl(vaultProperties.getUri()).build();
    }

    @Override
    public ResolvedSecret resolve(String credentialRef, UUID tenantId) {
        // If Vault is disabled or during local dev/tests, fallback to inMemoryResolver
        if (!vaultProperties.isEnabled()) {
            return inMemoryResolver.resolve(credentialRef, tenantId);
        }
        String cacheKey = tenantId + ":" + credentialRef;
        CachedSecret cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.secret();
        }
        ResolvedSecret secret = readFromVault(credentialRef);
        cache.put(cacheKey, new CachedSecret(secret, Instant.now().plusSeconds(vaultProperties.getCacheTtlSeconds())));
        return secret;
    }

    @Override
    public void putSecret(String credentialRef, UUID tenantId, ResolvedSecret secret) {
        inMemoryResolver.putSecret(credentialRef, tenantId, secret);
    }

    private ResolvedSecret readFromVault(String credentialRef) {
        String path = normalizePath(credentialRef);
        Map<?, ?> response = restClient.get()
                .uri("/v1/secret/data/" + path)
                .header("X-Vault-Token", vaultProperties.getToken())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);
        Object dataNode = response == null ? null : response.get("data");
        if (!(dataNode instanceof Map<?, ?> data)) {
            throw new SecretNotFoundException(credentialRef);
        }
        Object valuesNode = data.get("data");
        if (!(valuesNode instanceof Map<?, ?> values)) {
            throw new SecretNotFoundException(credentialRef);
        }

        String tokenUrl = value(values, "tokenUrl");
        if (tokenUrl == null) {
            tokenUrl = value(values, "token_url");
        }
        String clientId = value(values, "clientId");
        if (clientId == null) {
            clientId = value(values, "client_id");
        }
        String clientSecret = value(values, "clientSecret");
        if (clientSecret == null) {
            clientSecret = value(values, "client_secret");
        }
        String scope = value(values, "scope");
        if (tokenUrl != null && clientId != null && clientSecret != null) {
            return ResolvedSecret.oauth2(credentialRef, tokenUrl, clientId, clientSecret, scope);
        }

        String apiKey = value(values, "apiKey");
        if (apiKey == null) {
            apiKey = value(values, "api_key");
        }
        if (apiKey != null) {
            return ResolvedSecret.apiKey(credentialRef, apiKey);
        }

        String token = value(values, "token");
        if (token != null) {
            return ResolvedSecret.bearer(credentialRef, token);
        }

        String username = value(values, "username");
        String password = value(values, "password");
        if (username != null && password != null) {
            return ResolvedSecret.basic(credentialRef, username, password);
        }

        throw new SecretNotFoundException(credentialRef);
    }

    private String normalizePath(String credentialRef) {
        String path = credentialRef.startsWith("vault:") ? credentialRef.substring("vault:".length()) : credentialRef;
        if (path.startsWith("secret/data/")) {
            return path.substring("secret/data/".length());
        }
        if (path.startsWith("secret/")) {
            return path.substring("secret/".length());
        }
        return path;
    }

    private String value(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private record CachedSecret(ResolvedSecret secret, Instant expiresAt) {}
}
