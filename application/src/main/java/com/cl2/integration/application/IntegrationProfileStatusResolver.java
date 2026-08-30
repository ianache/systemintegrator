package com.cl2.integration.application;

import com.cl2.integration.domain.model.IntegrationProfileStatus;
import com.cl2.integration.integration.sync.SyncRunStatus;

public final class IntegrationProfileStatusResolver {

    private IntegrationProfileStatusResolver() {
    }

    public static IntegrationProfileStatus resolve(boolean active, boolean paused, SyncRunStatus lastRunStatus) {
        if (!active) {
            return IntegrationProfileStatus.INACTIVE;
        }
        if (paused) {
            return IntegrationProfileStatus.PAUSED;
        }
        if (lastRunStatus == null) {
            return IntegrationProfileStatus.DRAFT;
        }
        return switch (lastRunStatus) {
            case FAILED -> IntegrationProfileStatus.ERROR;
            case CANCELLED -> IntegrationProfileStatus.DEGRADED;
            case SUCCESS -> IntegrationProfileStatus.ACTIVE;
        };
    }
}
