package com.cl2.integration.integration.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
    DEAD_LETTER,
    CANCELLED
}
