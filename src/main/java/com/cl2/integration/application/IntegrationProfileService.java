package com.cl2.integration.application;

import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.application.command.UpdateIntegrationProfileCommand;
import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationProfileService {

    private final IntegrationProfileRepository repository;

    public IntegrationProfileService(IntegrationProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IntegrationProfileView create(UUID tenantId, CreateIntegrationProfileCommand command) {
        if (repository.existsActive(tenantId, command.businessDomain(), command.externalSource())) {
            throw new IntegrationProfileConflictException("An active integration profile already exists for this domain and source");
        }
        IntegrationProfile profile = IntegrationProfile.create(UUID.randomUUID(), tenantId, command.businessDomain(),
                command.externalSource(), command.direction(), command.sourceOfTruth());
        return toView(repository.save(tenantId, profile));
    }

    @Transactional(readOnly = true)
    public List<IntegrationProfileView> list(UUID tenantId, boolean activeOnly) {
        return repository.findAll(tenantId, activeOnly).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public IntegrationProfileView get(UUID tenantId, UUID profileId) {
        return toView(repository.findById(tenantId, profileId));
    }

    @Transactional
    public IntegrationProfileView update(UUID tenantId, UUID profileId, UpdateIntegrationProfileCommand command) {
        IntegrationProfile profile = repository.findById(tenantId, profileId);
        if (profile.active() && identifiesDifferentActiveProfile(profile, command)
                && repository.existsActive(tenantId, command.businessDomain(), command.externalSource())) {
            throw new IntegrationProfileConflictException("An active integration profile already exists for this domain and source");
        }
        IntegrationProfile updated = profile.update(command.businessDomain(), command.externalSource(), command.direction(),
                command.sourceOfTruth(), command.expectedVersion());
        return toView(repository.save(tenantId, updated));
    }

    @Transactional
    public void deactivate(UUID tenantId, UUID profileId) {
        IntegrationProfile profile = repository.findById(tenantId, profileId);
        repository.save(tenantId, profile.deactivate());
    }

    private IntegrationProfileView toView(IntegrationProfile profile) {
        return new IntegrationProfileView(profile.id(), profile.tenantId(), profile.businessDomain(), profile.externalSource(),
                profile.direction(), profile.sourceOfTruth(), profile.active(), profile.createdAt(), profile.updatedAt(),
                profile.version());
    }

    private boolean identifiesDifferentActiveProfile(IntegrationProfile profile, UpdateIntegrationProfileCommand command) {
        return !profile.businessDomain().equals(command.businessDomain())
                || !profile.externalSource().equals(command.externalSource());
    }
}
