package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.FlowExecution;
import java.time.Instant;
import java.util.UUID;

public record FlowExecutionResponse(
        UUID id,
        UUID flowId,
        int flowVersionNumber,
        String status,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String errorMessage) {

    public static FlowExecutionResponse from(FlowExecution execution) {
        return new FlowExecutionResponse(execution.id(), execution.flowId(), execution.flowVersionNumber(),
                execution.status().name(), execution.startedAt(), execution.finishedAt(), execution.durationMs(),
                execution.errorMessage());
    }
}
