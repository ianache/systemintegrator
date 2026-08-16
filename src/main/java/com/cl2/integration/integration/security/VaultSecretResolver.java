package com.cl2.integration.integration.security;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Primary
public class VaultSecretResolver implements SecretResolver {
    private final VaultProperties vaultProperties;
    private final InMemorySecretResolver inMemoryResolver;

    public VaultSecretResolver(VaultProperties vaultProperties, InMemorySecretResolver inMemoryResolver) {
        this.vaultProperties = vaultProperties;
        this.inMemoryResolver = inMemoryResolver;
    }

    @Override
    public ResolvedSecret resolve(String credentialRef, UUID tenantId) {
        // If Vault is disabled or during local dev/tests, fallback to inMemoryResolver
        if (!vaultProperties.isEnabled()) {
            return inMemoryResolver.resolve(credentialRef, tenantId);
        }
        try {
            return inMemoryResolver.resolve(credentialRef, tenantId);
        } catch (SecretNotFoundException ex) {
            throw ex;
        }
    }

    @Override
    public void putSecret(String credentialRef, UUID tenantId, ResolvedSecret secret) {
        inMemoryResolver.putSecret(credentialRef, tenantId, secret);
    }
}
