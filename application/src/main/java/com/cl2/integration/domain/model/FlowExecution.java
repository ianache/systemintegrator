package com.cl2.integration.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class FlowExecution {

    private final UUID id;
    private final UUID tenantId;
    private final UUID flowId;
    private final int flowVersionNumber;
    private final FlowExecutionStatus status;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final long durationMs;
    private final String errorMessage;

    private FlowExecution(UUID id, UUID tenantId, UUID flowId, int flowVersionNumber, FlowExecutionStatus status,
                          Instant startedAt, Instant finishedAt, String errorMessage) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.flowVersionNumber = flowVersionNumber;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not be before startedAt");
        }
        this.durationMs = Duration.between(startedAt, finishedAt).toMillis();
        this.errorMessage = errorMessage;
    }

    public static FlowExecution report(UUID id, UUID tenantId, UUID flowId, int flowVersionNumber,
                                       FlowExecutionStatus status, Instant startedAt, Instant finishedAt,
                                       String errorMessage) {
        return new FlowExecution(id, tenantId, flowId, flowVersionNumber, status, startedAt, finishedAt,
                errorMessage);
    }

    public static FlowExecution rehydrate(UUID id, UUID tenantId, UUID flowId, int flowVersionNumber,
                                          FlowExecutionStatus status, Instant startedAt, Instant finishedAt,
                                          String errorMessage) {
        return new FlowExecution(id, tenantId, flowId, flowVersionNumber, status, startedAt, finishedAt,
                errorMessage);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID flowId() { return flowId; }
    public int flowVersionNumber() { return flowVersionNumber; }
    public FlowExecutionStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public long durationMs() { return durationMs; }
    public String errorMessage() { return errorMessage; }
}
