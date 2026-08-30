package com.cl2.integration.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class FlowVersion {

    private final UUID id;
    private final UUID flowId;
    private final UUID tenantId;
    private final int versionNumber;
    private final String graph;
    private final FlowVersionState state;
    private final String publishedBy;
    private final Instant publishedAt;

    private FlowVersion(UUID id, UUID flowId, UUID tenantId, int versionNumber, String graph,
                        FlowVersionState state, String publishedBy, Instant publishedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be at least 1");
        }
        this.versionNumber = versionNumber;
        this.graph = requireNonBlank(graph, "graph");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.publishedBy = requireNonBlank(publishedBy, "publishedBy");
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }

    public static FlowVersion publish(UUID id, UUID flowId, UUID tenantId, int versionNumber, String graph,
                                       String publishedBy) {
        return new FlowVersion(id, flowId, tenantId, versionNumber, graph, FlowVersionState.ACTIVE, publishedBy,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    public static FlowVersion rehydrate(UUID id, UUID flowId, UUID tenantId, int versionNumber, String graph,
                                        FlowVersionState state, String publishedBy, Instant publishedAt) {
        return new FlowVersion(id, flowId, tenantId, versionNumber, graph, state, publishedBy, publishedAt);
    }

    public FlowVersion withState(FlowVersionState newState) {
        return new FlowVersion(id, flowId, tenantId, versionNumber, graph, newState, publishedBy, publishedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID flowId() {
        return flowId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public String graph() {
        return graph;
    }

    public FlowVersionState state() {
        return state;
    }

    public String publishedBy() {
        return publishedBy;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
