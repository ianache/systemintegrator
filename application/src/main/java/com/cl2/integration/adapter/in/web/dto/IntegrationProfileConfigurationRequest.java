package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record IntegrationProfileConfigurationRequest(
        IntegrationProtocol protocol,
        String connector,
        String adapter,
        String endpoint,
        String credentialRef,
        JsonNode mapping,
        JsonNode transformation,
        JsonNode syncPolicy,
        JsonNode retryPolicy,
        JsonNode rateLimitPolicy,
        JsonNode extractionConfig
) {
    public boolean hasAnyConfiguration() {
        return protocol != null || connector != null || adapter != null || endpoint != null
                || credentialRef != null || mapping != null || transformation != null
                || syncPolicy != null || retryPolicy != null || rateLimitPolicy != null
                || extractionConfig != null;
    }

    public IntegrationProfileConfiguration toDomain(ObjectMapper objectMapper) {
        if (!hasAnyConfiguration()) {
            return null;
        }
        return new IntegrationProfileConfiguration(
                protocol,
                connector,
                adapter,
                endpoint,
                credentialRef,
                toJsonString(mapping, objectMapper),
                toJsonString(transformation, objectMapper),
                toJsonString(syncPolicy, objectMapper),
                toJsonString(retryPolicy, objectMapper),
                toJsonString(rateLimitPolicy, objectMapper),
                toJsonString(extractionConfig, objectMapper)
        );
    }

    private static String toJsonString(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse configuration JSON node", e);
        }
    }
}
