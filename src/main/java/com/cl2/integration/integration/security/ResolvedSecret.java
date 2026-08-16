package com.cl2.integration.integration.security;

import java.util.Map;

public record ResolvedSecret(
    String credentialRef,
    AuthType authType,
    String username,
    String password,
    String apiKey,
    String token,
    Map<String, String> headers
) {
    public ResolvedSecret {
        if (headers == null) {
            headers = Map.of();
        }
    }

    public static ResolvedSecret apiKey(String credentialRef, String apiKey) {
        return new ResolvedSecret(credentialRef, AuthType.API_KEY, null, null, apiKey, null, Map.of());
    }

    public static ResolvedSecret bearer(String credentialRef, String token) {
        return new ResolvedSecret(credentialRef, AuthType.BEARER, null, null, null, token, Map.of());
    }

    public static ResolvedSecret basic(String credentialRef, String username, String password) {
        return new ResolvedSecret(credentialRef, AuthType.BASIC, username, password, null, null, Map.of());
    }
}
