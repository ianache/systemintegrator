package com.cl2.integration.application;

import com.cl2.integration.domain.model.FlowVersionState;
import java.time.Instant;
import java.util.UUID;

public record FlowVersionView(
        UUID id,
        UUID flowId,
        int versionNumber,
        String graph,
        FlowVersionState state,
        String publishedBy,
        Instant publishedAt) {
}
