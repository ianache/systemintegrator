package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import com.cl2.integration.domain.model.FlowExecutionStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "flow_execution_step")
class FlowExecutionStepJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "flow_execution_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID flowExecutionId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FlowExecutionStatus status;

    @Column(name = "started_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    protected FlowExecutionStepJpaEntity() {
    }

    private FlowExecutionStepJpaEntity(FlowExecutionStep step) {
        this.id = step.id();
        this.flowExecutionId = step.flowExecutionId();
        this.nodeId = step.nodeId();
        this.status = step.status();
        this.startedAt = step.startedAt().truncatedTo(ChronoUnit.MICROS);
        this.durationMs = step.durationMs();
        this.errorMessage = step.errorMessage();
        this.stepOrder = step.stepOrder();
    }

    static FlowExecutionStepJpaEntity from(FlowExecutionStep step) {
        return new FlowExecutionStepJpaEntity(step);
    }

    FlowExecutionStep toDomain() {
        return new FlowExecutionStep(id, flowExecutionId, nodeId, status, startedAt, durationMs, errorMessage, stepOrder);
    }
}
