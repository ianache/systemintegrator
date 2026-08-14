package com.cl2.integration.domain.model;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationProfileTest {

    private static final UUID PROFILE_ID = UUID.fromString("3c32c264-9163-4985-a3df-cb67a1031039");
    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

    @Test
    void createBuildsAnActiveProfileAtVersionZero() {
        IntegrationProfile profile = createProfile();

        assertThat(profile.id()).isEqualTo(PROFILE_ID);
        assertThat(profile.tenantId()).isEqualTo(TENANT_ID);
        assertThat(profile.businessDomain()).isEqualTo("orders");
        assertThat(profile.externalSource()).isEqualTo("erp");
        assertThat(profile.direction()).isEqualTo(SyncDirection.BIDIRECTIONAL);
        assertThat(profile.sourceOfTruth()).isEqualTo(SourceOfTruth.INTERNAL);
        assertThat(profile.active()).isTrue();
        assertThat(profile.createdAt()).isNotNull();
        assertThat(profile.updatedAt()).isNotNull();
        assertThat(profile.version()).isZero();
    }

    @Test
    void createRejectsNullTenant() {
        assertThatThrownBy(() -> IntegrationProfile.create(
                PROFILE_ID, null, "orders", "erp", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void createRejectsBlankBusinessDomain() {
        assertThatThrownBy(() -> IntegrationProfile.create(
                PROFILE_ID, TENANT_ID, "  ", "erp", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsBlankExternalSource() {
        assertThatThrownBy(() -> IntegrationProfile.create(
                PROFILE_ID, TENANT_ID, "orders", "", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsNullEnums() {
        assertThatThrownBy(() -> IntegrationProfile.create(
                PROFILE_ID, TENANT_ID, "orders", "erp", null, SourceOfTruth.EXTERNAL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IntegrationProfile.create(
                PROFILE_ID, TENANT_ID, "orders", "erp", SyncDirection.INBOUND, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deactivateReturnsAnInactiveProfileAndIsIdempotent() {
        IntegrationProfile deactivated = createProfile().deactivate();

        assertThat(deactivated.active()).isFalse();
        assertThat(deactivated.version()).isEqualTo(1);
        assertThat(deactivated.deactivate()).isSameAs(deactivated);
        assertThat(deactivated.version()).isEqualTo(1);
    }

    @Test
    void updateIncrementsVersionWhenExpectedVersionMatches() {
        IntegrationProfile profile = createProfile();

        IntegrationProfile updated = profile.update(
                "invoices", "billing", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, 0);

        assertThat(updated.businessDomain()).isEqualTo("invoices");
        assertThat(updated.externalSource()).isEqualTo("billing");
        assertThat(updated.direction()).isEqualTo(SyncDirection.OUTBOUND);
        assertThat(updated.sourceOfTruth()).isEqualTo(SourceOfTruth.EXTERNAL);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(profile.version()).isZero();
    }

    @Test
    void updateRejectsAMismatchedExpectedVersion() {
        assertThatThrownBy(() -> createProfile().update(
                "invoices", "billing", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    private IntegrationProfile createProfile() {
        return IntegrationProfile.create(
                PROFILE_ID, TENANT_ID, "orders", "erp", SyncDirection.BIDIRECTIONAL, SourceOfTruth.INTERNAL);
    }
}
