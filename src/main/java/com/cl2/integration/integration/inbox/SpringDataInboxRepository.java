package com.cl2.integration.integration.inbox;

import java.util.UUID;
import org.springframework.data.repository.Repository;

interface SpringDataInboxRepository extends Repository<InboxJpaEntity, UUID> {
    boolean existsById(UUID eventId);
    InboxJpaEntity save(InboxJpaEntity entity);
}
