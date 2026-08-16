package com.cl2.integration.integration.inbox;

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
@Table(name = "integration_inbox")
public class InboxJpaEntity {
    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(name = "event_id", columnDefinition = "BINARY(16)")
    private UUID eventId;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(columnDefinition = "JSON")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected InboxJpaEntity() {}

    public InboxJpaEntity(UUID eventId, UUID tenantId, String eventType, String payload, String status, int attempts, Instant receivedAt) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.receivedAt = receivedAt != null ? receivedAt.truncatedTo(ChronoUnit.MICROS) : null;
    }

    public InboxJpaEntity(UUID eventId, UUID tenantId, String eventType) {
        this(eventId, tenantId, eventType, null, "RECEIVED", 0, Instant.now());
    }

    public UUID getEventId() { return eventId; }
    public UUID getTenantId() { return tenantId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }

    public void markProcessed(Instant processedAt) {
        this.status = "PROCESSED";
        this.processedAt = processedAt != null ? processedAt.truncatedTo(ChronoUnit.MICROS) : null;
        this.lastError = null;
    }

    public void markDeadLetter(String error) {
        this.attempts++;
        this.status = "DEAD_LETTER";
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 997) + "..." : error;
    }

    public void recordAttempt(String error) {
        this.attempts++;
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 997) + "..." : error;
    }
}
