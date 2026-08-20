package com.cl2.integration.domain.model.customer;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CustomerUpdatedEvent(
    UUID eventId,
    UUID tenantId,
    String customerId,
    String taxId,
    String legalName,
    String tradeName,
    String email,
    String phone,
    String address,
    String countryCode,
    Instant occurredAt
) {
    public CustomerUpdatedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
    }

    public static CustomerUpdatedEvent from(Customer customer) {
        return new CustomerUpdatedEvent(
            UUID.randomUUID(),
            customer.tenantId(),
            customer.customerId(),
            customer.taxId(),
            customer.legalName(),
            customer.tradeName(),
            customer.email(),
            customer.phone(),
            customer.address(),
            customer.countryCode(),
            Instant.now()
        );
    }
}
