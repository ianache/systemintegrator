package com.cl2.integration.integration.lookup.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ValueLookupRepository {

    ValueLookup save(ValueLookup lookup);

    Optional<String> findTargetValue(UUID tenantId, String externalSource, String catalogCode, String sourceValue);

    List<ValueLookup> findAll(UUID tenantId, String externalSource, String catalogCode);

    void deleteById(UUID tenantId, UUID id);
}
