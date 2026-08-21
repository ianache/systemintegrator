package com.cl2.integration.integration.lookup.adapter.in.web.dto;

import com.cl2.integration.integration.lookup.domain.ValueLookup;
import java.time.Instant;
import java.util.UUID;

public record ValueLookupResponse(
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
    public static ValueLookupResponse from(ValueLookup lookup) {
        return new ValueLookupResponse(
                lookup.id(),
                lookup.tenantId(),
                lookup.externalSource(),
                lookup.catalogCode(),
                lookup.sourceValue(),
                lookup.targetValue(),
                lookup.description(),
                lookup.active(),
                lookup.createdAt(),
                lookup.updatedAt()
        );
    }
}
