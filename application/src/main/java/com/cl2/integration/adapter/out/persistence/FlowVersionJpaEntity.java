package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.model.FlowVersionState;
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
@Table(name = "flow_version")
class FlowVersionJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "flow_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID flowId;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "graph", nullable = false, columnDefinition = "JSON")
    private String graph;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private FlowVersionState state;

    @Column(name = "published_by", nullable = false, length = 255)
    private String publishedBy;

    @Column(name = "published_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant publishedAt;

    protected FlowVersionJpaEntity() {
    }

    private FlowVersionJpaEntity(FlowVersion flowVersion) {
        this.id = flowVersion.id();
        this.flowId = flowVersion.flowId();
        this.tenantId = flowVersion.tenantId();
        this.versionNumber = flowVersion.versionNumber();
        this.graph = flowVersion.graph();
        this.state = flowVersion.state();
        this.publishedBy = flowVersion.publishedBy();
        this.publishedAt = flowVersion.publishedAt().truncatedTo(ChronoUnit.MICROS);
    }

    static FlowVersionJpaEntity from(FlowVersion flowVersion) {
        return new FlowVersionJpaEntity(flowVersion);
    }

    FlowVersion toDomain() {
        return FlowVersion.rehydrate(id, flowId, tenantId, versionNumber, graph, state, publishedBy, publishedAt);
    }
}
