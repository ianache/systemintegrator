package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateIntegrationProfileRequest(
        @NotBlank String businessDomain,
        @NotBlank String externalSource,
        @NotNull SyncDirection syncDirection,
        @NotNull SourceOfTruth sourceOfTruth,
        long expectedVersion) {
}
