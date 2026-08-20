package com.cl2.integration.integration.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySecretResolver implements SecretResolver {
    private final Map<String, ResolvedSecret> store = new ConcurrentHashMap<>();

    @Override
    public ResolvedSecret resolve(String credentialRef, UUID tenantId) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new SecretNotFoundException("null");
        }
        String key = buildKey(credentialRef, tenantId);
        ResolvedSecret secret = store.get(key);
        if (secret == null) {
            // fallback to global key
            secret = store.get(credentialRef);
        }
        if (secret == null) {
            throw new SecretNotFoundException(credentialRef);
        }
        return secret;
    }

    @Override
    public void putSecret(String credentialRef, UUID tenantId, ResolvedSecret secret) {
        store.put(buildKey(credentialRef, tenantId), secret);
        store.put(credentialRef, secret);
    }

    private String buildKey(String credentialRef, UUID tenantId) {
        return (tenantId != null ? tenantId.toString() : "global") + ":" + credentialRef;
    }
}
