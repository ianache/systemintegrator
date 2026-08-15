package com.cl2.integration.integration.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, UUID tenantId, UUID aggregateId, String aggregateType, String eventType,
                          String payload, String status, int attempts, Instant availableAt, Instant publishedAt,
                          String lastError, Instant createdAt) {
    public static OutboxEvent pending(UUID tenantId, UUID aggregateId, String aggregateType, String eventType, String payload) {
        Instant now = Instant.now();
        return new OutboxEvent(UUID.randomUUID(), tenantId, aggregateId, aggregateType, eventType, payload,
                "PENDING", 0, now, null, null, now);
    }
}
