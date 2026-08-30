package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.FlowVersionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

public record FlowVersionResponse(
        UUID id,
        UUID flowId,
        int versionNumber,
        JsonNode graph,
        String state,
        String publishedBy,
        Instant publishedAt) {

    public static FlowVersionResponse from(FlowVersionView view, ObjectMapper objectMapper) {
        JsonNode graph;
        try {
            graph = objectMapper.readTree(view.graph());
        } catch (Exception e) {
            graph = null;
        }
        return new FlowVersionResponse(view.id(), view.flowId(), view.versionNumber(), graph,
                view.state().name(), view.publishedBy(), view.publishedAt());
    }
}
