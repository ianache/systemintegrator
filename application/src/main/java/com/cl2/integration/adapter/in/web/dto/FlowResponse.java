package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.FlowView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

public record FlowResponse(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode draftGraph,
        String triggerSummary,
        Integer activeVersionNumber,
        String status,
        int nodeCount,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static FlowResponse from(FlowView view, ObjectMapper objectMapper) {
        return new FlowResponse(view.id(), view.tenantId(), view.code(), view.name(),
                readTree(view.draftGraph(), objectMapper), view.triggerSummary(), view.activeVersionNumber(),
                view.status().name(), view.nodeCount(), view.archived(), view.createdAt(), view.updatedAt(),
                view.version());
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FlowResponse.class);

    private static JsonNode readTree(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse stored draftGraph JSON: {}", e.getMessage());
            return null;
        }
    }
}
