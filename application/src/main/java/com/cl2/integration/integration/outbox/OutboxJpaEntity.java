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
public class OutboxJpaEntity {
    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "aggregate_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(name = "topic", length = 150)
    private String topic;

    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxJpaEntity() {}

    private OutboxJpaEntity(OutboxEvent event) {
        this.id = event.id();
        this.tenantId = event.tenantId();
        this.aggregateType = event.aggregateType();
        this.aggregateId = event.aggregateId();
        this.eventType = event.eventType();
        this.topic = event.topic();
        this.payload = event.payload();
        this.status = event.status();
        this.attempts = event.attempts();
        this.availableAt = event.availableAt() != null ? event.availableAt().truncatedTo(ChronoUnit.MICROS) : null;
        this.publishedAt = event.publishedAt() != null ? event.publishedAt().truncatedTo(ChronoUnit.MICROS) : null;
        this.lastError = event.lastError();
        this.createdAt = event.createdAt() != null ? event.createdAt().truncatedTo(ChronoUnit.MICROS) : null;
    }

    public static OutboxJpaEntity from(OutboxEvent event) {
        return new OutboxJpaEntity(event);
    }

    public OutboxEvent toDomain() {
        return new OutboxEvent(id, tenantId, aggregateId, aggregateType, eventType, topic, payload, status, attempts, availableAt, publishedAt, lastError, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }

    public void markPublished(Instant publishedAt) {
        this.status = "PUBLISHED";
        this.publishedAt = publishedAt != null ? publishedAt.truncatedTo(ChronoUnit.MICROS) : null;
        this.lastError = null;
    }

    public void retryNow(Instant availableAt) {
        this.status = "PENDING";
        this.attempts = 0;
        this.lastError = null;
        this.availableAt = availableAt != null ? availableAt.truncatedTo(ChronoUnit.MICROS) : null;
    }

    public void markFailed(String error, Instant nextAvailableAt, boolean terminal) {
        this.attempts++;
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 997) + "..." : error;
        this.availableAt = nextAvailableAt != null ? nextAvailableAt.truncatedTo(ChronoUnit.MICROS) : null;
        if (terminal) {
            this.status = "FAILED";
        }
    }
}
