package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.FlowMetricsService.FlowExecutionWithSteps;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FlowExecutionDetailResponse(
        UUID id,
        UUID flowId,
        int flowVersionNumber,
        String status,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String errorMessage,
        List<FlowExecutionStepResponse> steps) {

    public static FlowExecutionDetailResponse from(FlowExecutionWithSteps executionWithSteps) {
        var execution = executionWithSteps.execution();
        return new FlowExecutionDetailResponse(execution.id(), execution.flowId(), execution.flowVersionNumber(),
                execution.status().name(), execution.startedAt(), execution.finishedAt(), execution.durationMs(),
                execution.errorMessage(),
                executionWithSteps.steps().stream().map(FlowExecutionStepResponse::from).toList());
    }
}
