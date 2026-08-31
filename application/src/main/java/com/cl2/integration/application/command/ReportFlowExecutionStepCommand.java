package com.cl2.integration.application.command;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;

public record ReportFlowExecutionStepCommand(
        String nodeId,
        FlowExecutionStatus status,
        Instant startedAt,
        long durationMs,
        String errorMessage) {
}
