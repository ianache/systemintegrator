package com.cl2.integration.e2e;

import com.cl2.integration.adapter.in.web.dto.CreateIntegrationProfileRequest;
import com.cl2.integration.adapter.in.web.dto.UpdateIntegrationProfileRequest;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.fasterxml.jackson.databind.JsonNode;

final class IntegrationProfilePayloads {

    private IntegrationProfilePayloads() {
    }

    static CreateIntegrationProfileRequest create(String businessDomain, String externalSource) {
        return new CreateIntegrationProfileRequest(
                businessDomain, externalSource, SyncDirection.INBOUND, SourceOfTruth.PLATFORM,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    static CreateIntegrationProfileRequest createWithConfig(
            String businessDomain, String externalSource, IntegrationProtocol protocol,
            String connector, String adapter, String endpoint, String credentialRef,
            JsonNode mapping, JsonNode retryPolicy, JsonNode rateLimitPolicy) {
        return new CreateIntegrationProfileRequest(
                businessDomain, externalSource, SyncDirection.INBOUND, SourceOfTruth.PLATFORM,
                protocol, connector, adapter, endpoint, credentialRef, mapping, null, null,
                retryPolicy, rateLimitPolicy, null);
    }

    static UpdateIntegrationProfileRequest update(String businessDomain, String externalSource, long expectedVersion) {
        return new UpdateIntegrationProfileRequest(
                businessDomain, externalSource, SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, expectedVersion,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
