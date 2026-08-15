package com.cl2.integration.integration.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "integration_inbox")
class InboxJpaEntity {
    @Id @JdbcTypeCode(Types.BINARY) @Column(name = "event_id", columnDefinition = "BINARY(16)") private UUID eventId;
    @JdbcTypeCode(Types.BINARY) @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)") private UUID tenantId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private int attempts;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    protected InboxJpaEntity() { }
    InboxJpaEntity(UUID eventId, UUID tenantId, String eventType) {
        this.eventId = eventId; this.tenantId = tenantId; this.eventType = eventType; this.status = "RECEIVED";
        this.attempts = 0; this.receivedAt = Instant.now();
    }
}
