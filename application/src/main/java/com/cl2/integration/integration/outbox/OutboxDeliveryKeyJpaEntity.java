package com.cl2.integration.integration.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "integration_outbox_delivery_key")
class OutboxDeliveryKeyJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(name = "delivery_id", columnDefinition = "BINARY(16)")
    private UUID deliveryId;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "outbox_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID outboxId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxDeliveryKeyJpaEntity() {
    }

    OutboxDeliveryKeyJpaEntity(UUID deliveryId, UUID tenantId, UUID outboxId, Instant createdAt) {
        this.deliveryId = deliveryId;
        this.tenantId = tenantId;
        this.outboxId = outboxId;
        this.createdAt = createdAt;
    }

    UUID getDeliveryId() {
        return deliveryId;
    }
}
