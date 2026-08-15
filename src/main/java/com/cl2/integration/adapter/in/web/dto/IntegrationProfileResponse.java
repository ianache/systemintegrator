package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import java.time.Instant;
import java.util.UUID;

public record IntegrationProfileResponse(
        UUID id,
        UUID tenantId,
        String businessDomain,
        String externalSource,
        SyncDirection syncDirection,
        SourceOfTruth sourceOfTruth,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static IntegrationProfileResponse from(IntegrationProfileView view) {
        return new IntegrationProfileResponse(view.id(), view.tenantId(), view.businessDomain(), view.externalSource(),
                view.direction(), view.sourceOfTruth(), view.active(), view.createdAt(), view.updatedAt(), view.version());
    }
}
