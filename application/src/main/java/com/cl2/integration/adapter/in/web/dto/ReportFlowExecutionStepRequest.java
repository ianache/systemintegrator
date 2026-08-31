package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ReportFlowExecutionStepRequest(
        @NotNull String nodeId,
        @NotNull FlowExecutionStatus status,
        @NotNull Instant startedAt,
        long durationMs,
        String errorMessage) {
}
