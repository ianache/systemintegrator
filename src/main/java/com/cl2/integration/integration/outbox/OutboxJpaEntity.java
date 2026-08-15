package com.cl2.integration.integration.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "integration_outbox")
class OutboxJpaEntity {
    @Id @JdbcTypeCode(Types.BINARY) @Column(columnDefinition = "BINARY(16)") private UUID id;
    @JdbcTypeCode(Types.BINARY) @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)") private UUID tenantId;
    @Column(name = "aggregate_type", nullable = false) private String aggregateType;
    @JdbcTypeCode(Types.BINARY) @Column(name = "aggregate_id", nullable = false, columnDefinition = "BINARY(16)") private UUID aggregateId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(nullable = false, columnDefinition = "json") private String payload;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private int attempts;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "last_error") private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected OutboxJpaEntity() { }
    private OutboxJpaEntity(OutboxEvent event) {
        id = event.id(); tenantId = event.tenantId(); aggregateType = event.aggregateType(); aggregateId = event.aggregateId();
        eventType = event.eventType(); payload = event.payload(); status = event.status(); attempts = event.attempts();
        availableAt = event.availableAt().truncatedTo(ChronoUnit.MICROS); publishedAt = event.publishedAt();
        lastError = event.lastError(); createdAt = event.createdAt().truncatedTo(ChronoUnit.MICROS);
    }
    static OutboxJpaEntity from(OutboxEvent event) { return new OutboxJpaEntity(event); }
}
