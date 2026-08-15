package com.cl2.integration.integration.inbox;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class InboxPersistenceAdapter implements InboxStore {
    private final SpringDataInboxRepository repository;
    InboxPersistenceAdapter(SpringDataInboxRepository repository) { this.repository = repository; }
    @Override
    public boolean recordIfAbsent(UUID eventId, UUID tenantId, String eventType) {
        if (repository.existsById(eventId)) return false;
        try { repository.save(new InboxJpaEntity(eventId, tenantId, eventType)); return true; }
        catch (DataIntegrityViolationException duplicate) { return false; }
    }
}
