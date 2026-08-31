package com.cl2.integration.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One node visited during a FlowExecution. Deliberately minimal (status +
 * start/duration/error, no per-step input/output payload capture) — there is
 * no internal flow-running engine in this codebase yet (FlowExecution itself
 * is only ever created via the external-facing "report" endpoint, by
 * whatever system actually ran the flow), so this mirrors that same
 * report-after-the-fact shape rather than inventing telemetry no caller can
 * populate yet.
 */
public record FlowExecutionStep(
        UUID id,
        UUID flowExecutionId,
        String nodeId,
        FlowExecutionStatus status,
        Instant startedAt,
        long durationMs,
        String errorMessage,
        int stepOrder) {

    public FlowExecutionStep {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(flowExecutionId, "flowExecutionId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
    }
}
