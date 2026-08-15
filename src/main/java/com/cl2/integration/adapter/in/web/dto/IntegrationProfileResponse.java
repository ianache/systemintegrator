package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

public record IntegrationProfileResponse(
        UUID id,
        UUID tenantId,
        String businessDomain,
        String externalSource,
        SyncDirection syncDirection,
        SourceOfTruth sourceOfTruth,
        @JsonInclude(JsonInclude.Include.NON_NULL) ConfigurationResponse configuration,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static IntegrationProfileResponse from(IntegrationProfileView view, ObjectMapper objectMapper) {
        ConfigurationResponse configResponse = ConfigurationResponse.from(view.configuration(), objectMapper);
        return new IntegrationProfileResponse(view.id(), view.tenantId(), view.businessDomain(), view.externalSource(),
                view.direction(), view.sourceOfTruth(), configResponse, view.active(), view.createdAt(), view.updatedAt(), view.version());
    }

    public static IntegrationProfileResponse from(IntegrationProfileView view) {
        return from(view, new ObjectMapper());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfigurationResponse(
            IntegrationProtocol protocol,
            String connector,
            String adapter,
            String endpoint,
            String credentialRef,
            JsonNode mapping,
            JsonNode transformation,
            JsonNode syncPolicy,
            JsonNode retryPolicy,
            JsonNode rateLimitPolicy
    ) {
        public static ConfigurationResponse from(IntegrationProfileConfiguration config, ObjectMapper objectMapper) {
            if (config == null) {
                return null;
            }
            return new ConfigurationResponse(
                    config.protocol(),
                    config.connector(),
                    config.adapter(),
                    config.endpoint(),
                    config.credentialRef(),
                    readTree(config.mapping(), objectMapper),
                    readTree(config.transformation(), objectMapper),
                    readTree(config.syncPolicy(), objectMapper),
                    readTree(config.retryPolicy(), objectMapper),
                    readTree(config.rateLimitPolicy(), objectMapper)
            );
        }

        private static JsonNode readTree(String json, ObjectMapper objectMapper) {
            if (json == null) {
                return null;
            }
            try {
                return objectMapper.readTree(json);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
    }
}
