package com.cl2.integration.application.command;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;

public record ReportFlowExecutionCommand(
        int flowVersionNumber,
        FlowExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage) {
}
