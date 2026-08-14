package com.cl2.integration.application.command;

import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;

public record UpdateIntegrationProfileCommand(
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth,
        long expectedVersion) {
}
