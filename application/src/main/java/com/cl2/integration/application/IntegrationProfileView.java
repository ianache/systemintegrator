package com.cl2.integration.application;

import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import java.time.Instant;
import java.util.UUID;

public record IntegrationProfileView(
        UUID id,
        UUID tenantId,
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth,
        IntegrationProfileConfiguration configuration,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public IntegrationProfileView(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                  SyncDirection direction, SourceOfTruth sourceOfTruth,
                                  boolean active, Instant createdAt, Instant updatedAt, long version) {
        this(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, null, active, createdAt, updatedAt, version);
    }
}
