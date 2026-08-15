package com.cl2.integration.integration.outbox;

import java.util.UUID;
import org.springframework.data.repository.Repository;

interface SpringDataOutboxRepository extends Repository<OutboxJpaEntity, UUID> {
    OutboxJpaEntity save(OutboxJpaEntity entity);
}
