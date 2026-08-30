package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ReportFlowExecutionRequest(
        @NotNull Integer flowVersionNumber,
        @NotNull FlowExecutionStatus status,
        @NotNull Instant startedAt,
        @NotNull Instant finishedAt,
        String errorMessage) {
}
