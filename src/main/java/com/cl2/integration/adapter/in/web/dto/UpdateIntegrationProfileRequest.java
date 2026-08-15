package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateIntegrationProfileRequest(
        @NotBlank String businessDomain,
        @NotBlank String externalSource,
        @NotNull SyncDirection syncDirection,
        @NotNull SourceOfTruth sourceOfTruth,
        @NotNull @PositiveOrZero Long expectedVersion,
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
    public IntegrationProfileConfigurationRequest configurationRequest() {
        return new IntegrationProfileConfigurationRequest(
                protocol, connector, adapter, endpoint, credentialRef,
                mapping, transformation, syncPolicy, retryPolicy, rateLimitPolicy
        );
    }
}
