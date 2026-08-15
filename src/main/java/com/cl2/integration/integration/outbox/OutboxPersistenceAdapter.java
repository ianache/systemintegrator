package com.cl2.integration.integration.outbox;

import org.springframework.stereotype.Repository;

@Repository
class OutboxPersistenceAdapter implements OutboxRepository {
    private final SpringDataOutboxRepository repository;
    OutboxPersistenceAdapter(SpringDataOutboxRepository repository) { this.repository = repository; }
    @Override public OutboxEvent save(OutboxEvent event) { repository.save(OutboxJpaEntity.from(event)); return event; }
}
