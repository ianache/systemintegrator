package com.cl2.integration.application.command;

import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;

public record CreateIntegrationProfileCommand(
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth,
        IntegrationProfileConfiguration configuration) {

    public CreateIntegrationProfileCommand(
            String businessDomain,
            String externalSource,
            SyncDirection direction,
            SourceOfTruth sourceOfTruth) {
        this(businessDomain, externalSource, direction, sourceOfTruth, null);
    }
}
