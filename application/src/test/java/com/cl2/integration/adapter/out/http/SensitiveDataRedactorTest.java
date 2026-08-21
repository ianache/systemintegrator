package com.cl2.integration.adapter.out.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {

    @Test
    @DisplayName("Should redact secret values keeping first 3 chars and suffixing [REDACTED]")
    void shouldRedactSecretValues() {
        assertThat(SensitiveDataRedactor.redact("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
                .isEqualTo("eyJ[REDACTED]");
        assertThat(SensitiveDataRedactor.redact("secret-password-123"))
                .isEqualTo("sec[REDACTED]");
        assertThat(SensitiveDataRedactor.redact("abc"))
                .isEqualTo("abc[REDACTED]");
        assertThat(SensitiveDataRedactor.redact("ab"))
                .isEqualTo("ab[REDACTED]");
        assertThat(SensitiveDataRedactor.redact("a"))
                .isEqualTo("a[REDACTED]");
        assertThat(SensitiveDataRedactor.redact(""))
                .isEqualTo("[REDACTED]");
        assertThat(SensitiveDataRedactor.redact(null))
                .isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("Should redact Authorization and sensitive HTTP headers")
    void shouldRedactSensitiveHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.xyz");
        headers.put("X-API-Key", "my-secret-api-key-12345");
        headers.put("X-Custom-Token", "tok_secret_998877");
        headers.put("X-Distribuidor-Id", "1");
        headers.put("x-audit", "424234234");

        Map<String, String> sanitized = SensitiveDataRedactor.redactHeaders(headers);

        assertThat(sanitized.get("Content-Type")).isEqualTo("application/json");
        assertThat(sanitized.get("Accept")).isEqualTo("application/json");
        assertThat(sanitized.get("X-Distribuidor-Id")).isEqualTo("1");
        assertThat(sanitized.get("x-audit")).isEqualTo("424234234");
        assertThat(sanitized.get("Authorization")).isEqualTo("Bearer eyJ[REDACTED]");
        assertThat(sanitized.get("X-API-Key")).isEqualTo("my-[REDACTED]");
        assertThat(sanitized.get("X-Custom-Token")).isEqualTo("tok[REDACTED]");
    }

    @Test
    @DisplayName("Should redact sensitive fields in JSON payload")
    void shouldRedactSensitiveJsonFields() {
        String json = """
                {
                  "username": "admin_user",
                  "password": "superSecretPassword123",
                  "client_secret": "mySecretClientSecret",
                  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
                  "alias": "C2Q145",
                  "fuenteId": 1
                }
                """;

        String redactedJson = SensitiveDataRedactor.redactJsonPayload(json);

        assertThat(redactedJson).contains("\"username\" : \"admin_user\"");
        assertThat(redactedJson).contains("\"alias\" : \"C2Q145\"");
        assertThat(redactedJson).contains("\"fuenteId\" : 1");
        assertThat(redactedJson).contains("\"password\" : \"sup[REDACTED]\"");
        assertThat(redactedJson).contains("\"client_secret\" : \"myS[REDACTED]\"");
        assertThat(redactedJson).contains("\"token\" : \"eyJ[REDACTED]\"");
    }
}
