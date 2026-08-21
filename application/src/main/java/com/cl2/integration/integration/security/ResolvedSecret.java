package com.cl2.integration.integration.security;

import java.util.Map;

public record ResolvedSecret(
    String credentialRef,
    AuthType authType,
    String username,
    String password,
    String apiKey,
    String token,
    String tokenUrl,
    String clientId,
    String clientSecret,
    String scope,
    Map<String, String> headers
) {
    public ResolvedSecret {
        if (headers == null) {
            headers = Map.of();
        }
    }

    public ResolvedSecret(
        String credentialRef,
        AuthType authType,
        String username,
        String password,
        String apiKey,
        String token,
        Map<String, String> headers
    ) {
        this(credentialRef, authType, username, password, apiKey, token, null, null, null, null, headers);
    }

    public static ResolvedSecret apiKey(String credentialRef, String apiKey) {
        return new ResolvedSecret(credentialRef, AuthType.API_KEY, null, null, apiKey, null, null, null, null, null, Map.of());
    }

    public static ResolvedSecret bearer(String credentialRef, String token) {
        return new ResolvedSecret(credentialRef, AuthType.BEARER, null, null, null, token, null, null, null, null, Map.of());
    }

    public static ResolvedSecret basic(String credentialRef, String username, String password) {
        return new ResolvedSecret(credentialRef, AuthType.BASIC, username, password, null, null, null, null, null, null, Map.of());
    }

    public static ResolvedSecret oauth2(String credentialRef, String tokenUrl, String clientId, String clientSecret, String scope, Map<String, String> headers) {
        return new ResolvedSecret(credentialRef, AuthType.OAUTH2_CLIENT_CREDENTIALS, null, null, null, null, tokenUrl, clientId, clientSecret, scope, headers);
    }

    public static ResolvedSecret oauth2(String credentialRef, String tokenUrl, String clientId, String clientSecret, String scope) {
        return oauth2(credentialRef, tokenUrl, clientId, clientSecret, scope, Map.of());
    }

    public static ResolvedSecret oauth2(String credentialRef, String tokenUrl, String clientId, String clientSecret) {
        return oauth2(credentialRef, tokenUrl, clientId, clientSecret, null, Map.of());
    }
}

