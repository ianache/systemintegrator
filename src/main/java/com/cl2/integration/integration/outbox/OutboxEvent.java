package com.cl2.integration.integration.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
    UUID id,
    UUID tenantId,
    UUID aggregateId,
    String aggregateType,
    String eventType,
    String topic,
    String payload,
    String status,
    int attempts,
    Instant availableAt,
    Instant publishedAt,
    String lastError,
    Instant createdAt
) {
    public static OutboxEvent pending(UUID tenantId, UUID aggregateId, String aggregateType, String eventType, String topic, String payload) {
        Instant now = Instant.now();
        return new OutboxEvent(UUID.randomUUID(), tenantId, aggregateId, aggregateType, eventType, topic, payload, "PENDING", 0, now, null, null, now);
    }

    public static OutboxEvent pending(UUID tenantId, UUID aggregateId, String aggregateType, String eventType, String payload) {
        return pending(tenantId, aggregateId, aggregateType, eventType, "integration.events", payload);
    }
}
