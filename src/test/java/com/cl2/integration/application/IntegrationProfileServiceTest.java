package com.cl2.integration.application;

import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.application.command.UpdateIntegrationProfileCommand;
import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationProfileServiceTest {

    @Test
    void createSavesAnActiveProfileForTheCallingTenant() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        IntegrationProfileService service = new IntegrationProfileService(repository);

        IntegrationProfileView view = service.create(tenantId, createCommand("customer", "sap"));

        assertThat(view.tenantId()).isEqualTo(tenantId);
        assertThat(view.businessDomain()).isEqualTo("customer");
        assertThat(view.externalSource()).isEqualTo("sap");
        assertThat(view.active()).isTrue();
        assertThat(view.version()).isZero();
        assertThat(repository.existsActiveTenantIds).containsExactly(tenantId);
        assertThat(repository.saveTenantIds).containsExactly(tenantId);
    }

    @Test
    void createRejectsADuplicateActiveProfileWithinTheSameTenant() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        repository.save(tenantId, profile(tenantId, "customer", "sap"));
        IntegrationProfileService service = new IntegrationProfileService(repository);

        assertThatThrownBy(() -> service.create(tenantId, createCommand("customer", "sap")))
            .isInstanceOf(IntegrationProfileConflictException.class);

        assertThat(repository.existsActiveTenantIds).containsExactly(tenantId);
        assertThat(repository.profiles).hasSize(1);
    }

    @Test
    void getTreatsAnotherTenantsProfileAsNotFound() {
        UUID ownerTenantId = UUID.randomUUID();
        UUID requestingTenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        IntegrationProfile profile = profile(ownerTenantId, "customer", "sap");
        repository.save(ownerTenantId, profile);
        IntegrationProfileService service = new IntegrationProfileService(repository);

        assertThatThrownBy(() -> service.get(requestingTenantId, profile.id()))
            .isInstanceOf(IntegrationProfileNotFoundException.class);

        assertThat(repository.findByIdTenantIds).containsExactly(requestingTenantId);
    }

    @Test
    void getReportsNotFoundWhenNoTenantScopedProfileExists() {
        UUID tenantId = UUID.randomUUID();
        IntegrationProfileService service = new IntegrationProfileService(new InMemoryRepository());

        assertThatThrownBy(() -> service.get(tenantId, UUID.randomUUID()))
            .isInstanceOf(IntegrationProfileNotFoundException.class);
    }

    @Test
    void updateSavesTheChangedProfileUsingTheCallingTenant() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        IntegrationProfile profile = profile(tenantId, "customer", "sap");
        repository.save(tenantId, profile);
        IntegrationProfileService service = new IntegrationProfileService(repository);

        IntegrationProfileView view = service.update(tenantId, profile.id(),
            new UpdateIntegrationProfileCommand("vehicle", "sigo", SyncDirection.INBOUND, SourceOfTruth.PLATFORM, 0));

        assertThat(view.businessDomain()).isEqualTo("vehicle");
        assertThat(view.version()).isEqualTo(1);
        assertThat(repository.findByIdTenantIds).containsExactly(tenantId);
        assertThat(repository.saveTenantIds).containsExactly(tenantId, tenantId);
        assertThat(repository.existsActiveTenantIds).containsExactly(tenantId);
    }

    @Test
    void deactivatePersistsLogicalDeactivationUsingTheCallingTenant() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        IntegrationProfile profile = profile(tenantId, "customer", "sap");
        repository.save(tenantId, profile);
        IntegrationProfileService service = new IntegrationProfileService(repository);

        service.deactivate(tenantId, profile.id());

        assertThat(repository.profiles.get(profile.id()).active()).isFalse();
        assertThat(repository.findByIdTenantIds).containsExactly(tenantId);
        assertThat(repository.saveTenantIds).containsExactly(tenantId, tenantId);
    }

    @Test
    void listUsesTheCallingTenantForTheRepositoryQuery() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        repository.save(tenantId, profile(tenantId, "customer", "sap"));
        IntegrationProfileService service = new IntegrationProfileService(repository);

        List<IntegrationProfileView> profiles = service.list(tenantId, true);

        assertThat(profiles).hasSize(1);
        assertThat(repository.findAllTenantIds).containsExactly(tenantId);
    }

    private CreateIntegrationProfileCommand createCommand(String businessDomain, String externalSource) {
        return new CreateIntegrationProfileCommand(
            businessDomain, externalSource, SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL);
    }

    private IntegrationProfile profile(UUID tenantId, String businessDomain, String externalSource) {
        return IntegrationProfile.create(
            UUID.randomUUID(), tenantId, businessDomain, externalSource, SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL);
    }

    private static final class InMemoryRepository implements IntegrationProfileRepository {

        private final Map<UUID, IntegrationProfile> profiles = new HashMap<>();
        private final List<UUID> saveTenantIds = new ArrayList<>();
        private final List<UUID> findByIdTenantIds = new ArrayList<>();
        private final List<UUID> findAllTenantIds = new ArrayList<>();
        private final List<UUID> existsActiveTenantIds = new ArrayList<>();

        @Override
        public IntegrationProfile save(UUID tenantId, IntegrationProfile profile) {
            saveTenantIds.add(tenantId);
            profiles.put(profile.id(), profile);
            return profile;
        }

        @Override
        public Optional<IntegrationProfile> findById(UUID tenantId, UUID id) {
            findByIdTenantIds.add(tenantId);
            return Optional.ofNullable(profiles.get(id)).filter(profile -> profile.tenantId().equals(tenantId));
        }

        @Override
        public List<IntegrationProfile> findAll(UUID tenantId, boolean activeOnly) {
            findAllTenantIds.add(tenantId);
            return profiles.values().stream()
                .filter(profile -> profile.tenantId().equals(tenantId))
                .filter(profile -> !activeOnly || profile.active())
                .toList();
        }

        @Override
        public boolean existsActive(UUID tenantId, String businessDomain, String externalSource) {
            existsActiveTenantIds.add(tenantId);
            return profiles.values().stream().anyMatch(profile ->
                profile.tenantId().equals(tenantId)
                    && profile.active()
                    && profile.businessDomain().equals(businessDomain)
                    && profile.externalSource().equals(externalSource));
        }
    }
}
