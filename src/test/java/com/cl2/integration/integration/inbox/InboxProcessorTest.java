package com.cl2.integration.integration.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxProcessorTest {
    @Test
    void processesAnEventOnlyOnceWhenTheSameIdIsReceivedAgain() {
        InMemoryInboxStore store = new InMemoryInboxStore();
        InboxProcessor processor = new InboxProcessor(store);
        UUID eventId = UUID.randomUUID();

        assertThat(processor.accept(eventId, UUID.randomUUID(), "vehicle.created")).isTrue();
        assertThat(processor.accept(eventId, UUID.randomUUID(), "vehicle.created")).isFalse();
        assertThat(store.receivedCount()).isEqualTo(1);
    }

    private static final class InMemoryInboxStore implements InboxStore {
        private final java.util.Set<UUID> ids = new java.util.HashSet<>();
        @Override public boolean recordIfAbsent(UUID eventId, UUID tenantId, String eventType) { return ids.add(eventId); }
        int receivedCount() { return ids.size(); }
    }
}
