package com.cl2.integration.integration.outbox;

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface SpringDataOutboxDeliveryKeyRepository extends Repository<OutboxDeliveryKeyJpaEntity, UUID> {

    OutboxDeliveryKeyJpaEntity save(OutboxDeliveryKeyJpaEntity entity);

    List<OutboxDeliveryKeyJpaEntity> findByTenantIdAndDeliveryIdIn(
            UUID tenantId,
            Collection<UUID> deliveryIds);
}
