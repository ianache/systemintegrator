package com.cl2.integration.application;

import com.cl2.integration.domain.model.IntegrationProfileStatus;
import com.cl2.integration.integration.sync.SyncRunStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationProfileStatusResolverTest {

    @Test
    void inactiveWinsOverEverythingElse() {
        assertThat(IntegrationProfileStatusResolver.resolve(false, true, SyncRunStatus.FAILED))
                .isEqualTo(IntegrationProfileStatus.INACTIVE);
    }

    @Test
    void pausedWinsOverSyncState() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, true, SyncRunStatus.FAILED))
                .isEqualTo(IntegrationProfileStatus.PAUSED);
    }

    @Test
    void draftWhenNoSyncStateExistsYet() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, false, null))
                .isEqualTo(IntegrationProfileStatus.DRAFT);
    }

    @Test
    void failedSyncMeansError() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, false, SyncRunStatus.FAILED))
                .isEqualTo(IntegrationProfileStatus.ERROR);
    }

    @Test
    void cancelledSyncMeansDegraded() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, false, SyncRunStatus.CANCELLED))
                .isEqualTo(IntegrationProfileStatus.DEGRADED);
    }

    @Test
    void successfulSyncMeansActive() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, false, SyncRunStatus.SUCCESS))
                .isEqualTo(IntegrationProfileStatus.ACTIVE);
    }
}
