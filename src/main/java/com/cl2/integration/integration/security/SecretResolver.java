package com.cl2.integration.integration.security;

import java.util.UUID;

public interface SecretResolver {
    ResolvedSecret resolve(String credentialRef, UUID tenantId);
    void putSecret(String credentialRef, UUID tenantId, ResolvedSecret secret);
}
