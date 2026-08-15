package com.cl2.integration.e2e;

import com.cl2.integration.adapter.in.web.dto.CreateIntegrationProfileRequest;
import com.cl2.integration.adapter.in.web.dto.UpdateIntegrationProfileRequest;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;

final class IntegrationProfilePayloads {

    private IntegrationProfilePayloads() {
    }

    static CreateIntegrationProfileRequest create(String businessDomain, String externalSource) {
        return new CreateIntegrationProfileRequest(
                businessDomain, externalSource, SyncDirection.INBOUND, SourceOfTruth.PLATFORM);
    }

    static UpdateIntegrationProfileRequest update(String businessDomain, String externalSource, long expectedVersion) {
        return new UpdateIntegrationProfileRequest(
                businessDomain, externalSource, SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, expectedVersion);
    }
}
