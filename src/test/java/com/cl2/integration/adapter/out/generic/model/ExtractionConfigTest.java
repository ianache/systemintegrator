package com.cl2.integration.adapter.out.generic.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseJdbcExtractionConfig() throws Exception {
        String json = """
            {
                "query": "SELECT * FROM KNA1 WHERE AEDAT >= :lastSyncWithBuffer",
                "watermarkParam": "lastSyncWithBuffer",
                "keyColumn": "KUNNR",
                "fetchSize": 500
            }
            """;
        ExtractionConfig config = objectMapper.readValue(json, ExtractionConfig.class);
        assertEquals("SELECT * FROM KNA1 WHERE AEDAT >= :lastSyncWithBuffer", config.query());
        assertEquals("lastSyncWithBuffer", config.watermarkParam());
        assertEquals("KUNNR", config.keyColumn());
        assertEquals(500, config.fetchSize());
    }

    @Test
    void shouldParseRestExtractionConfig() throws Exception {
        String json = """
            {
                "method": "POST",
                "path": "/api/v1/customers",
                "queryParams": { "status": "active" },
                "headers": { "Accept": "application/json" },
                "responseJsonPath": "$.data[*]",
                "watermarkFormat": "EPOCH_MILLIS",
                "keyProperty": "id"
            }
            """;
        ExtractionConfig config = objectMapper.readValue(json, ExtractionConfig.class);
        assertEquals("POST", config.method());
        assertEquals("/api/v1/customers", config.path());
        assertEquals(Map.of("status", "active"), config.queryParams());
        assertEquals(Map.of("Accept", "application/json"), config.headers());
        assertEquals("$.data[*]", config.responseJsonPath());
        assertEquals("EPOCH_MILLIS", config.watermarkFormat());
        assertEquals("id", config.keyProperty());
    }

    @Test
    void shouldVerifyDefaults() throws Exception {
        String json = "{}";
        ExtractionConfig config = objectMapper.readValue(json, ExtractionConfig.class);
        assertEquals("lastSyncWithBuffer", config.watermarkParam());
        assertEquals(500, config.fetchSize());
        assertEquals("GET", config.method());
        assertEquals("$", config.responseJsonPath());
        assertEquals("ISO_8601", config.watermarkFormat());
    }

    @Test
    void shouldParseOauth2AuthConfig() throws Exception {
        String json = """
            {
                "authType": "OAUTH2_CLIENT_CREDENTIALS",
                "tokenUrl": "https://oauth.test/token",
                "clientId": "my-client",
                "clientSecretRef": "secret/oauth",
                "scope": "read:all"
            }
            """;
        AuthConfig auth = objectMapper.readValue(json, AuthConfig.class);
        assertEquals("OAUTH2_CLIENT_CREDENTIALS", auth.authType());
        assertEquals("https://oauth.test/token", auth.tokenUrl());
        assertEquals("my-client", auth.clientId());
        assertEquals("secret/oauth", auth.clientSecretRef());
        assertEquals("read:all", auth.scope());
    }

    @Test
    void shouldParseApiKeyAuthConfig() throws Exception {
        String json = """
            {
                "authType": "API_KEY",
                "headerName": "X-API-Key",
                "keyRef": "secret/api-key"
            }
            """;
        AuthConfig auth = objectMapper.readValue(json, AuthConfig.class);
        assertEquals("API_KEY", auth.authType());
        assertEquals("X-API-Key", auth.headerName());
        assertEquals("secret/api-key", auth.keyRef());
    }

    @Test
    void shouldParseBasicAuthAuthConfig() throws Exception {
        String json = """
            {
                "authType": "BASIC_AUTH",
                "credentialRef": "secret/basic-auth"
            }
            """;
        AuthConfig auth = objectMapper.readValue(json, AuthConfig.class);
        assertEquals("BASIC_AUTH", auth.authType());
        assertEquals("secret/basic-auth", auth.credentialRef());
    }
}
