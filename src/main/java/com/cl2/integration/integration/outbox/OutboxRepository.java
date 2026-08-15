package com.cl2.integration.integration.outbox;

public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);
}
