package com.cl2.integration.domain.model.customer;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CustomerCreatedEvent(
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
    public CustomerCreatedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
    }

    public static CustomerCreatedEvent from(Customer customer) {
        return new CustomerCreatedEvent(
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
