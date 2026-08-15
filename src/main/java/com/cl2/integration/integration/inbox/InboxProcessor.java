package com.cl2.integration.integration.inbox;

import java.util.UUID;

public final class InboxProcessor {
    private final InboxStore store;
    public InboxProcessor(InboxStore store) { this.store = store; }
    public boolean accept(UUID eventId, UUID tenantId, String eventType) {
        return store.recordIfAbsent(eventId, tenantId, eventType);
    }
}
