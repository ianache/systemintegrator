package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.FlowExecutionStep;
import java.time.Instant;

public record FlowExecutionStepResponse(
        String nodeId,
        String status,
        Instant startedAt,
        long durationMs,
        String errorMessage,
        int stepOrder) {

    public static FlowExecutionStepResponse from(FlowExecutionStep step) {
        return new FlowExecutionStepResponse(step.nodeId(), step.status().name(), step.startedAt(),
                step.durationMs(), step.errorMessage(), step.stepOrder());
    }
}
