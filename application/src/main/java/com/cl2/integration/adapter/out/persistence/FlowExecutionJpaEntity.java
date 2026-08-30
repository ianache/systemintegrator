package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStatus;
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
@Table(name = "flow_execution")
class FlowExecutionJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "flow_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID flowId;

    @Column(name = "flow_version_number", nullable = false)
    private int flowVersionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FlowExecutionStatus status;

    @Column(name = "started_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant finishedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    protected FlowExecutionJpaEntity() {
    }

    private FlowExecutionJpaEntity(FlowExecution execution) {
        this.id = execution.id();
        this.tenantId = execution.tenantId();
        this.flowId = execution.flowId();
        this.flowVersionNumber = execution.flowVersionNumber();
        this.status = execution.status();
        this.startedAt = toMysqlTimestamp(execution.startedAt());
        this.finishedAt = toMysqlTimestamp(execution.finishedAt());
        this.durationMs = execution.durationMs();
        this.errorMessage = execution.errorMessage();
    }

    static FlowExecutionJpaEntity from(FlowExecution execution) {
        return new FlowExecutionJpaEntity(execution);
    }

    FlowExecution toDomain() {
        return FlowExecution.rehydrate(id, tenantId, flowId, flowVersionNumber, status, startedAt, finishedAt,
                errorMessage);
    }

    private static Instant toMysqlTimestamp(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
