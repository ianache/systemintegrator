package com.cl2.integration.application.command;

import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;

public record UpdateIntegrationProfileCommand(
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth,
        IntegrationProfileConfiguration configuration,
        long expectedVersion) {

    public UpdateIntegrationProfileCommand(
            String businessDomain,
            String externalSource,
            SyncDirection direction,
            SourceOfTruth sourceOfTruth,
            long expectedVersion) {
        this(businessDomain, externalSource, direction, sourceOfTruth, null, expectedVersion);
    }
}
