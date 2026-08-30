package com.cl2.integration.adapter.in.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateFlowDraftRequest(
        @NotBlank String name,
        String triggerSummary,
        JsonNode draftGraph,
        @NotNull Long expectedVersion) {
}
