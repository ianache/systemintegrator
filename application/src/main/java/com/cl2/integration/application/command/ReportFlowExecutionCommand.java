package com.cl2.integration.application.command;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;
import java.util.List;

public record ReportFlowExecutionCommand(
        int flowVersionNumber,
        FlowExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        List<ReportFlowExecutionStepCommand> steps) {

    public ReportFlowExecutionCommand {
        steps = steps != null ? steps : List.of();
    }

    public ReportFlowExecutionCommand(int flowVersionNumber, FlowExecutionStatus status, Instant startedAt,
            Instant finishedAt, String errorMessage) {
        this(flowVersionNumber, status, startedAt, finishedAt, errorMessage, List.of());
    }
}
