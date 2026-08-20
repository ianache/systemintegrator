package com.cl2.integration.domain.model.customer;

import java.util.Objects;
import java.util.UUID;

public record Customer(
    UUID tenantId,
    String customerId,
    String taxId,
    String legalName,
    String tradeName,
    String email,
    String phone,
    String address,
    String countryCode
) {
    public Customer {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
    }

    public static Customer create(
            UUID tenantId,
            String customerId,
            String taxId,
            String legalName,
            String tradeName,
            String email,
            String phone,
            String address,
            String countryCode) {
        return new Customer(
            tenantId,
            customerId,
            taxId,
            legalName,
            tradeName,
            email,
            phone,
            address,
            countryCode
        );
    }
}
