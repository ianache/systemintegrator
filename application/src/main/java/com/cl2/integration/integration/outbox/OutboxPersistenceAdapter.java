package com.cl2.integration.integration.outbox;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
class OutboxPersistenceAdapter implements OutboxRepository {
    private final SpringDataOutboxRepository repository;
    private final SpringDataOutboxDeliveryKeyRepository deliveryKeyRepository;

    OutboxPersistenceAdapter(
            SpringDataOutboxRepository repository,
            SpringDataOutboxDeliveryKeyRepository deliveryKeyRepository) {
        this.repository = repository;
        this.deliveryKeyRepository = deliveryKeyRepository;
    }

    @Override public OutboxEvent save(OutboxEvent event) { repository.save(OutboxJpaEntity.from(event)); return event; }

    @Override
    public OutboxEvent save(OutboxEvent event, List<UUID> deliveryIds) {
        OutboxEvent savedEvent = save(event);
        new LinkedHashSet<>(deliveryIds).forEach(deliveryId -> deliveryKeyRepository.save(
                new OutboxDeliveryKeyJpaEntity(
                        deliveryId,
                        event.tenantId(),
                        event.id(),
                        Instant.now())));
        return savedEvent;
    }

    @Override public java.util.Optional<OutboxEvent> findLatestByAggregateId(java.util.UUID tenantId, java.util.UUID aggregateId) {
        return repository.findLatestByTenantIdAndAggregateId(tenantId, aggregateId).map(OutboxJpaEntity::toDomain);
    }

    @Override
    public Set<UUID> findExistingDeliveryIds(UUID tenantId, Collection<UUID> deliveryIds) {
        if (deliveryIds.isEmpty()) {
            return Set.of();
        }
        return deliveryKeyRepository.findByTenantIdAndDeliveryIdIn(tenantId, deliveryIds).stream()
                .map(OutboxDeliveryKeyJpaEntity::getDeliveryId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
