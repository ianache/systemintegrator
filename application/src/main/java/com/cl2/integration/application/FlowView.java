package com.cl2.integration.application;

import com.cl2.integration.domain.model.FlowStatus;
import java.time.Instant;
import java.util.UUID;

public record FlowView(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        String draftGraph,
        String triggerSummary,
        Integer activeVersionNumber,
        FlowStatus status,
        int nodeCount,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
