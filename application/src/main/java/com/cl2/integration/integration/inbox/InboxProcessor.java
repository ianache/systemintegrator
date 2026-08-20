package com.cl2.integration.integration.inbox;

import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class InboxProcessor {
    private final InboxStore store;
    private final DeadLetterQueuePublisher dlqPublisher;

    public InboxProcessor(InboxStore store, DeadLetterQueuePublisher dlqPublisher) {
        this.store = store;
        this.dlqPublisher = dlqPublisher;
    }

    public boolean process(UUID eventId, UUID tenantId, String eventType, String payload, String topic, Consumer<String> domainHandler) {
        boolean isNew = store.recordIfAbsent(eventId, tenantId, eventType, payload);
        if (!isNew) {
            var existing = store.find(eventId, tenantId);
            if (existing.isPresent() && "PROCESSED".equals(existing.get().getStatus())) {
                return false; // already processed, idempotently ignore
            }
        }

        try {
            domainHandler.accept(payload);
            store.markProcessed(eventId, tenantId);
            return true;
        } catch (Exception ex) {
            store.markDeadLetter(eventId, tenantId, ex.getMessage());
            dlqPublisher.publishToDlq(topic, eventId, tenantId, payload, ex.getMessage());
            throw new RuntimeException("Inbox processing failed, forwarded to DLQ", ex);
        }
    }
}
