package com.cl2.integration.integration.lookup.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ValueLookup(
        UUID id,
        UUID tenantId,
        String externalSource,
        String catalogCode,
        String sourceValue,
        String targetValue,
        String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public ValueLookup {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(externalSource, "externalSource must not be null");
        Objects.requireNonNull(catalogCode, "catalogCode must not be null");
        Objects.requireNonNull(sourceValue, "sourceValue must not be null");
        Objects.requireNonNull(targetValue, "targetValue must not be null");
    }

    public static ValueLookup create(
            UUID id,
            UUID tenantId,
            String externalSource,
            String catalogCode,
            String sourceValue,
            String targetValue,
            String description,
            boolean active
    ) {
        Instant now = Instant.now();
        return new ValueLookup(
                id != null ? id : UUID.randomUUID(),
                tenantId,
                externalSource,
                catalogCode,
                sourceValue,
                targetValue,
                description,
                active,
                now,
                now
        );
    }

    public static ValueLookup rehydrate(
            UUID id,
            UUID tenantId,
            String externalSource,
            String catalogCode,
            String sourceValue,
            String targetValue,
            String description,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new ValueLookup(
                id,
                tenantId,
                externalSource,
                catalogCode,
                sourceValue,
                targetValue,
                description,
                active,
                createdAt,
                updatedAt
        );
    }
}
