package com.cl2.integration.integration.inbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class InboxPersistenceAdapter implements InboxStore {
    private final SpringDataInboxRepository repository;

    public InboxPersistenceAdapter(SpringDataInboxRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public boolean recordIfAbsent(UUID eventId, UUID tenantId, String eventType, String payload) {
        Optional<InboxJpaEntity> existing = repository.findByEventIdAndTenantId(eventId, tenantId);
        if (existing.isPresent()) {
            return false;
        }
        repository.save(new InboxJpaEntity(eventId, tenantId, eventType, payload, "RECEIVED", 0, Instant.now()));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboxJpaEntity> find(UUID eventId, UUID tenantId) {
        return repository.findByEventIdAndTenantId(eventId, tenantId);
    }

    @Override
    @Transactional
    public void markProcessed(UUID eventId, UUID tenantId) {
        repository.findByEventIdAndTenantId(eventId, tenantId).ifPresent(entity -> {
            entity.markProcessed(Instant.now());
            repository.save(entity);
        });
    }

    @Override
    @Transactional
    public void markDeadLetter(UUID eventId, UUID tenantId, String error) {
        repository.findByEventIdAndTenantId(eventId, tenantId).ifPresent(entity -> {
            entity.markDeadLetter(error);
            repository.save(entity);
        });
    }
}
