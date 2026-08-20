package com.cl2.integration.integration.inbox;

import java.util.Optional;
import java.util.UUID;

public interface InboxStore {
    boolean recordIfAbsent(UUID eventId, UUID tenantId, String eventType, String payload);
    Optional<InboxJpaEntity> find(UUID eventId, UUID tenantId);
    void markProcessed(UUID eventId, UUID tenantId);
    void markDeadLetter(UUID eventId, UUID tenantId, String error);
}
