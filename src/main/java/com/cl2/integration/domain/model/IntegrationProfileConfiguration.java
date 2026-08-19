package com.cl2.integration.domain.model;

import java.util.regex.Pattern;

public record IntegrationProfileConfiguration(
        IntegrationProtocol protocol,
        String connector,
        String adapter,
        String endpoint,
        String credentialRef,
        String mapping,
        String transformation,
        String syncPolicy,
        String retryPolicy,
        String rateLimitPolicy,
        String extractionConfig
) {
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(?i)\"password\"\\s*:");

    public IntegrationProfileConfiguration {
        if (protocol != null) {
            if (connector == null || connector.isBlank()) {
                throw new IllegalArgumentException("connector must not be blank when protocol is specified");
            }
            if (adapter == null || adapter.isBlank()) {
                throw new IllegalArgumentException("adapter must not be blank when protocol is specified");
            }
        }
        if (credentialRef != null && credentialRef.isBlank()) {
            throw new IllegalArgumentException("credentialRef must not be blank when specified");
        }
        validateNoPlaintextPassword(mapping, "mapping");
        validateNoPlaintextPassword(transformation, "transformation");
        validateNoPlaintextPassword(syncPolicy, "syncPolicy");
        validateNoPlaintextPassword(retryPolicy, "retryPolicy");
        validateNoPlaintextPassword(rateLimitPolicy, "rateLimitPolicy");
        validateNoPlaintextPassword(extractionConfig, "extractionConfig");
    }

    private static void validateNoPlaintextPassword(String value, String fieldName) {
        if (value != null && PASSWORD_PATTERN.matcher(value).find()) {
            throw new IllegalArgumentException(fieldName + " must not contain plaintext password fields");
        }
    }
}
