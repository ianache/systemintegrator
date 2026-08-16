package com.cl2.integration.integration.inbox;

import org.springframework.data.repository.Repository;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataInboxRepository extends Repository<InboxJpaEntity, UUID> {
    InboxJpaEntity save(InboxJpaEntity entity);
    Optional<InboxJpaEntity> findById(UUID eventId);
    Optional<InboxJpaEntity> findByEventIdAndTenantId(UUID eventId, UUID tenantId);
    boolean existsByEventIdAndTenantId(UUID eventId, UUID tenantId);
    boolean existsById(UUID eventId);
}
