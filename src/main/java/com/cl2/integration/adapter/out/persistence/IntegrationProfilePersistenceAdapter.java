package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class IntegrationProfilePersistenceAdapter implements IntegrationProfileRepository {

    private final SpringDataIntegrationProfileRepository repository;

    IntegrationProfilePersistenceAdapter(SpringDataIntegrationProfileRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public IntegrationProfile save(IntegrationProfile profile) {
        try {
            IntegrationProfileJpaEntity entity = repository.findByTenantIdAndId(profile.tenantId(), profile.id())
                    .map(existing -> {
                        existing.apply(profile);
                        return existing;
                    })
                    .orElseGet(() -> IntegrationProfileJpaEntity.from(profile));
            return repository.saveAndFlush(entity).toDomain();
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw new IntegrationProfileConflictException("Integration profile conflicts with an existing profile");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IntegrationProfile> findById(UUID tenantId, UUID id) {
        return repository.findByTenantIdAndId(tenantId, id).map(IntegrationProfileJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IntegrationProfile> findAll(UUID tenantId, boolean activeOnly) {
        List<IntegrationProfileJpaEntity> profiles = activeOnly
                ? repository.findAllByTenantIdAndActiveTrueOrderByCreatedAtDesc(tenantId)
                : repository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        return profiles.stream().map(IntegrationProfileJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActive(UUID tenantId, String businessDomain, String externalSource) {
        return repository.existsByTenantIdAndBusinessDomainAndExternalSourceAndActiveTrue(
                tenantId, businessDomain, externalSource);
    }
}
