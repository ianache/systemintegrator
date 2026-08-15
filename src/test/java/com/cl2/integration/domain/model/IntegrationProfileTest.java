package com.cl2.integration.domain.model;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationProfileTest {

    @Test
    void createBuildsAnActiveProfileAtVersionZero() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        IntegrationProfile profile = IntegrationProfile.create(
            id, tenantId, "customer", "sap", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL);

        assertThat(profile.id()).isEqualTo(id);
        assertThat(profile.tenantId()).isEqualTo(tenantId);
        assertThat(profile.active()).isTrue();
        assertThat(profile.version()).isZero();
        assertThat(profile.createdAt()).isNotNull();
        assertThat(profile.updatedAt()).isEqualTo(profile.createdAt());
    }

    @Test
    void createRejectsMissingAndBlankIdentityFields() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> IntegrationProfile.create(
            null, tenantId, "customer", "sap", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IntegrationProfile.create(
            id, null, "customer", "sap", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IntegrationProfile.create(
            id, tenantId, " ", "sap", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntegrationProfile.create(
            id, tenantId, "customer", " ", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntegrationProfile.create(
            id, tenantId, "customer", "sap", null, SourceOfTruth.EXTERNAL))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IntegrationProfile.create(
            id, tenantId, "customer", "sap", SyncDirection.OUTBOUND, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void updateRejectsInvalidFieldValuesWhenTheVersionMatches() {
        assertThatThrownBy(() -> profile().update(
            " ", "sigo", SyncDirection.INBOUND, SourceOfTruth.PLATFORM, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile().update(
            "vehicle", null, SyncDirection.INBOUND, SourceOfTruth.PLATFORM, 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> profile().update(
            "vehicle", "sigo", null, SourceOfTruth.PLATFORM, 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> profile().update(
            "vehicle", "sigo", SyncDirection.INBOUND, null, 0))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void updateChangesStateAndAdvancesTheVersionWhenExpectedVersionMatches() {
        IntegrationProfile profile = profile();

        profile.update("vehicle", "sigo", SyncDirection.BIDIRECTIONAL, SourceOfTruth.SHARED, 0);

        assertThat(profile.businessDomain()).isEqualTo("vehicle");
        assertThat(profile.externalSource()).isEqualTo("sigo");
        assertThat(profile.direction()).isEqualTo(SyncDirection.BIDIRECTIONAL);
        assertThat(profile.sourceOfTruth()).isEqualTo(SourceOfTruth.SHARED);
        assertThat(profile.version()).isEqualTo(1);
    }

    @Test
    void updateRejectsAStaleExpectedVersion() {
        IntegrationProfile profile = profile();

        assertThatThrownBy(() -> profile.update(
            "vehicle", "sigo", SyncDirection.INBOUND, SourceOfTruth.PLATFORM, 1))
            .isInstanceOf(IntegrationProfileConflictException.class);
    }

    @Test
    void deactivateMarksTheProfileInactiveWithoutPhysicalDeletion() {
        IntegrationProfile profile = profile();

        profile.deactivate();

        assertThat(profile.active()).isFalse();
        assertThat(profile.id()).isNotNull();
    }

    private IntegrationProfile profile() {
        return IntegrationProfile.create(
            UUID.randomUUID(), UUID.randomUUID(), "customer", "sap", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL);
    }
}
