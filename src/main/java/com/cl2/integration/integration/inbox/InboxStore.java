package com.cl2.integration.integration.inbox;

import java.util.UUID;

public interface InboxStore {
    boolean recordIfAbsent(UUID eventId, UUID tenantId, String eventType);
}
