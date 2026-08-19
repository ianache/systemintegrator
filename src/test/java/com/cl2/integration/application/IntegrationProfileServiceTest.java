package com.cl2.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.application.command.UpdateIntegrationProfileCommand;
import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegrationProfileServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("b129386f-2ec1-4f2a-8d09-f2aed3b154c2");

    private FakeIntegrationProfileRepository repository;
    private IntegrationProfileService service;

    @BeforeEach
    void setUp() {
        repository = new FakeIntegrationProfileRepository();
        service = new IntegrationProfileService(repository, event -> { });
    }

    @Test
    void createsAnActiveProfileForTheSuppliedTenant() {
        IntegrationProfileView created = service.create(TENANT_ID, createCommand("orders", "erp"));

        assertThat(created.tenantId()).isEqualTo(TENANT_ID);
        assertThat(created.businessDomain()).isEqualTo("orders");
        assertThat(created.externalSource()).isEqualTo("erp");
        assertThat(created.active()).isTrue();
        assertThat(repository.savedProfiles).singleElement().extracting(IntegrationProfile::tenantId).isEqualTo(TENANT_ID);
        assertThat(repository.requestedTenantIds).containsOnly(TENANT_ID);
    }

    @Test
    void createsAnActiveProfileWithConfiguration() {
        IntegrationProfileConfiguration configuration = sampleConfiguration();
        CreateIntegrationProfileCommand command = new CreateIntegrationProfileCommand(
                "orders", "erp", SyncDirection.INBOUND, SourceOfTruth.PLATFORM, configuration);

        IntegrationProfileView created = service.create(TENANT_ID, command);

        assertThat(created.configuration()).isEqualTo(configuration);
    }

    @Test
    void listsProfilesUsingTheSuppliedTenantAndActiveFilter() {
        repository.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));
        repository.save(TENANT_ID, profile(TENANT_ID, "catalog", "crm").deactivate());

        List<IntegrationProfileView> profiles = service.list(TENANT_ID, true);

        assertThat(profiles).extracting(IntegrationProfileView::businessDomain).containsExactly("orders");
        assertThat(repository.requestedTenantIds).containsOnly(TENANT_ID);
    }

    @Test
    void getsAProfileUsingTheSuppliedTenant() {
        IntegrationProfile profile = repository.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));

        IntegrationProfileView found = service.get(TENANT_ID, profile.id());

        assertThat(found.id()).isEqualTo(profile.id());
        assertThat(repository.requestedTenantIds).containsOnly(TENANT_ID);
    }

    @Test
    void updatesAProfileUsingTheSuppliedTenant() {
        IntegrationProfile profile = repository.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));

        IntegrationProfileView updated = service.update(TENANT_ID, profile.id(),
                new UpdateIntegrationProfileCommand("catalog", "crm", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, 0));

        assertThat(updated.businessDomain()).isEqualTo("catalog");
        assertThat(updated.externalSource()).isEqualTo("crm");
        assertThat(updated.direction()).isEqualTo(SyncDirection.OUTBOUND);
        assertThat(updated.sourceOfTruth()).isEqualTo(SourceOfTruth.EXTERNAL);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(repository.requestedTenantIds).containsOnly(TENANT_ID);
        assertThat(repository.savedProfiles).allSatisfy(saved -> assertThat(saved.tenantId()).isEqualTo(TENANT_ID));
    }

    @Test
    void updatesAProfileWithConfigurationAndIncrementsVersion() {
        IntegrationProfile profile = repository.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));
        IntegrationProfileConfiguration updatedConfig = sampleConfiguration();

        IntegrationProfileView updated = service.update(TENANT_ID, profile.id(),
                new UpdateIntegrationProfileCommand("catalog", "crm", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, updatedConfig, 0));

        assertThat(updated.configuration()).isEqualTo(updatedConfig);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void deactivatesAProfileUsingTheSuppliedTenant() {
        IntegrationProfile profile = repository.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));

        service.deactivate(TENANT_ID, profile.id());

        assertThat(repository.findById(TENANT_ID, profile.id()).active()).isFalse();
        assertThat(repository.requestedTenantIds).containsOnly(TENANT_ID);
        assertThat(repository.savedProfiles).allSatisfy(saved -> assertThat(saved.tenantId()).isEqualTo(TENANT_ID));
    }

    @Test
    void rejectsCreationOfADuplicateActiveProfile() {
        repository.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));

        assertThatThrownBy(() -> service.create(TENANT_ID, createCommand("orders", "erp")))
                .isInstanceOf(IntegrationProfileConflictException.class);

        assertThat(repository.requestedTenantIds).containsOnly(TENANT_ID);
    }

    @Test
    void rejectsUpdatingAnActiveProfileToAnotherActiveProfilesDomainAndSource() {
        repository.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));
        IntegrationProfile profile = repository.save(TENANT_ID, profile(TENANT_ID, "catalog", "crm"));
        repository.clearRecordedTenantIds();

        assertThatThrownBy(() -> service.update(TENANT_ID, profile.id(),
                new UpdateIntegrationProfileCommand("orders", "erp", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, 0)))
                .isInstanceOf(IntegrationProfileConflictException.class);

        assertThat(repository.requestedTenantIds).containsOnly(TENANT_ID);
    }

    @Test
    void propagatesNotFoundWhenTheProfileDoesNotExistForTheSuppliedTenant() {
        UUID profileId = UUID.fromString("7b4fe930-a3ce-43c1-9297-ff7a3c60f80c");

        assertThatThrownBy(() -> service.get(TENANT_ID, profileId))
                .isInstanceOf(IntegrationProfileNotFoundException.class);

        assertThat(repository.requestedTenantIds).containsOnly(TENANT_ID);
    }

    @Test
    void doesNotExposeAProfileFromAnotherTenant() {
        IntegrationProfile profile = repository.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));
        repository.clearRecordedTenantIds();

        assertThatThrownBy(() -> service.get(OTHER_TENANT_ID, profile.id()))
                .isInstanceOf(IntegrationProfileNotFoundException.class);

        assertThat(repository.requestedTenantIds).containsOnly(OTHER_TENANT_ID);
    }

    private CreateIntegrationProfileCommand createCommand(String businessDomain, String externalSource) {
        return new CreateIntegrationProfileCommand(businessDomain, externalSource, SyncDirection.INBOUND, SourceOfTruth.PLATFORM);
    }

    private IntegrationProfile profile(UUID tenantId, String businessDomain, String externalSource) {
        return IntegrationProfile.create(UUID.randomUUID(), tenantId, businessDomain, externalSource,
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM);
    }

    private IntegrationProfileConfiguration sampleConfiguration() {
        return new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-vehicle-http", "https://sigo.test/api", "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}", null, null, "{\"maxAttempts\":3,\"initialBackoffMs\":100}",
                "{\"requestsPerSecond\":10}", null);
    }

    private static final class FakeIntegrationProfileRepository implements IntegrationProfileRepository {

        private final Map<UUID, IntegrationProfile> profiles = new HashMap<>();
        private final List<UUID> requestedTenantIds = new ArrayList<>();
        private final List<IntegrationProfile> savedProfiles = new ArrayList<>();

        @Override
        public IntegrationProfile save(UUID tenantId, IntegrationProfile profile) {
            requestedTenantIds.add(tenantId);
            if (!tenantId.equals(profile.tenantId())) {
                throw new IllegalArgumentException("tenantId must match the profile tenantId");
            }
            savedProfiles.add(profile);
            profiles.put(profile.id(), profile);
            return profile;
        }

        @Override
        public IntegrationProfile findById(UUID tenantId, UUID id) {
            requestedTenantIds.add(tenantId);
            IntegrationProfile profile = profiles.get(id);
            if (profile == null || !profile.tenantId().equals(tenantId)) {
                throw new IntegrationProfileNotFoundException("Integration profile was not found");
            }
            return profile;
        }

        @Override
        public List<IntegrationProfile> findAll(UUID tenantId, boolean activeOnly) {
            requestedTenantIds.add(tenantId);
            return profiles.values().stream()
                    .filter(profile -> profile.tenantId().equals(tenantId))
                    .filter(profile -> !activeOnly || profile.active())
                    .toList();
        }

        @Override
        public boolean existsActive(UUID tenantId, String businessDomain, String externalSource) {
            requestedTenantIds.add(tenantId);
            return profiles.values().stream().anyMatch(profile -> profile.tenantId().equals(tenantId)
                    && profile.businessDomain().equals(businessDomain)
                    && profile.externalSource().equals(externalSource)
                    && profile.active());
        }

        @Override
        public List<IntegrationProfile> findAllActiveByProtocol(IntegrationProtocol protocol) {
            return profiles.values().stream()
                    .filter(IntegrationProfile::active)
                    .filter(profile -> profile.configuration() != null && profile.configuration().protocol() == protocol)
                    .toList();
        }

        private void clearRecordedTenantIds() {
            requestedTenantIds.clear();
        }
    }
}
