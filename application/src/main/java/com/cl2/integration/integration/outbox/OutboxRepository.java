package com.cl2.integration.integration.outbox;

public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);
    java.util.Optional<OutboxEvent> findLatestByAggregateId(java.util.UUID tenantId, java.util.UUID aggregateId);
}
