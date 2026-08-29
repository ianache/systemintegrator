package com.cl2.integration.integration.outbox;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);
    OutboxEvent save(OutboxEvent event, List<UUID> deliveryIds);
    java.util.Optional<OutboxEvent> findLatestByAggregateId(UUID tenantId, UUID aggregateId);
    Set<UUID> findExistingDeliveryIds(UUID tenantId, Collection<UUID> deliveryIds);
}
