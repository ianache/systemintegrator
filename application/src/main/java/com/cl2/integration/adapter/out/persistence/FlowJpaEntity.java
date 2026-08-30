package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.Flow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "flow")
class FlowJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "code", nullable = false, length = 150)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "draft_graph", columnDefinition = "JSON")
    private String draftGraph;

    @Column(name = "trigger_summary", length = 100)
    private String triggerSummary;

    @Column(name = "active_version_number")
    private Integer activeVersionNumber;

    @Column(nullable = false)
    private boolean archived;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant updatedAt;

    protected FlowJpaEntity() {
    }

    private FlowJpaEntity(Flow flow) {
        this.id = flow.id();
        this.tenantId = flow.tenantId();
        this.code = flow.code();
        this.name = flow.name();
        this.draftGraph = flow.draftGraph();
        this.triggerSummary = flow.triggerSummary();
        this.activeVersionNumber = flow.activeVersionNumber();
        this.archived = flow.archived();
        this.version = flow.version();
        this.createdAt = toMysqlTimestamp(flow.createdAt());
        this.updatedAt = toMysqlTimestamp(flow.updatedAt());
    }

    static FlowJpaEntity from(Flow flow) {
        return new FlowJpaEntity(flow);
    }

    Flow toDomain() {
        return Flow.rehydrate(id, tenantId, code, name, draftGraph, triggerSummary, activeVersionNumber, archived,
                createdAt, updatedAt, version);
    }

    private static Instant toMysqlTimestamp(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
