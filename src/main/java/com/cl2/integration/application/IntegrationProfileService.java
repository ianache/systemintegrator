package com.cl2.integration.application;

import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.application.command.UpdateIntegrationProfileCommand;
import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnBean(IntegrationProfileRepository.class)
public class IntegrationProfileService {

    private final IntegrationProfileRepository repository;

    public IntegrationProfileService(IntegrationProfileRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
    }

    @Transactional
    public IntegrationProfileView create(UUID tenantId, CreateIntegrationProfileCommand command) {
        requireTenantId(tenantId);
        Objects.requireNonNull(command, "command is required");
        if (repository.existsActive(tenantId, command.businessDomain(), command.externalSource())) {
            throw new IntegrationProfileConflictException("An active integration profile already exists");
        }
        IntegrationProfile profile = IntegrationProfile.create(
            UUID.randomUUID(), tenantId, command.businessDomain(), command.externalSource(),
            command.direction(), command.sourceOfTruth());
        return toView(repository.save(tenantId, profile));
    }

    public List<IntegrationProfileView> list(UUID tenantId, boolean activeOnly) {
        requireTenantId(tenantId);
        return repository.findAll(tenantId, activeOnly).stream()
            .filter(profile -> profile.tenantId().equals(tenantId))
            .map(this::toView)
            .toList();
    }

    public IntegrationProfileView get(UUID tenantId, UUID profileId) {
        return toView(requireProfile(tenantId, profileId));
    }

    @Transactional
    public IntegrationProfileView update(UUID tenantId, UUID profileId, UpdateIntegrationProfileCommand command) {
        Objects.requireNonNull(command, "command is required");
        IntegrationProfile profile = requireProfile(tenantId, profileId);
        boolean identityChanged = !profile.businessDomain().equals(command.businessDomain())
            || !profile.externalSource().equals(command.externalSource());
        if (profile.active() && identityChanged
            && repository.existsActive(tenantId, command.businessDomain(), command.externalSource())) {
            throw new IntegrationProfileConflictException("An active integration profile already exists");
        }
        profile.update(
            command.businessDomain(), command.externalSource(), command.direction(), command.sourceOfTruth(),
            command.expectedVersion());
        return toView(repository.save(tenantId, profile));
    }

    @Transactional
    public void deactivate(UUID tenantId, UUID profileId) {
        IntegrationProfile profile = requireProfile(tenantId, profileId);
        profile.deactivate();
        repository.save(tenantId, profile);
    }

    private IntegrationProfile requireProfile(UUID tenantId, UUID profileId) {
        requireTenantId(tenantId);
        Objects.requireNonNull(profileId, "profileId is required");
        return repository.findById(tenantId, profileId)
            .filter(profile -> profile.tenantId().equals(tenantId))
            .orElseThrow(IntegrationProfileNotFoundException::new);
    }

    private void requireTenantId(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId is required");
    }

    private IntegrationProfileView toView(IntegrationProfile profile) {
        return new IntegrationProfileView(
            profile.id(), profile.tenantId(), profile.businessDomain(), profile.externalSource(), profile.direction(),
            profile.sourceOfTruth(), profile.active(), profile.version(), profile.createdAt(), profile.updatedAt());
    }
}
