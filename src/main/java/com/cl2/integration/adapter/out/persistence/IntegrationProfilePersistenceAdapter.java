package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnBean(SpringDataIntegrationProfileRepository.class)
public class IntegrationProfilePersistenceAdapter implements IntegrationProfileRepository {

    private final SpringDataIntegrationProfileRepository repository;

    public IntegrationProfilePersistenceAdapter(SpringDataIntegrationProfileRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
    }

    @Override
    public IntegrationProfile save(UUID tenantId, IntegrationProfile profile) {
        requireTenantId(tenantId);
        Objects.requireNonNull(profile, "profile is required");
        if (!tenantId.equals(profile.tenantId())) {
            throw new IllegalArgumentException("profile tenant must match the repository tenant");
        }
        try {
            IntegrationProfileJpaEntity entity = repository.findByTenantIdAndId(tenantId, profile.id())
                .map(existing -> {
                    existing.updateFrom(profile);
                    return existing;
                })
                .orElseGet(() -> IntegrationProfileJpaEntity.fromNewProfile(profile));
            return repository.saveAndFlush(entity).toDomain();
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
            throw new IntegrationProfileConflictException("Integration profile persistence conflict");
        }
    }

    @Override
    public Optional<IntegrationProfile> findById(UUID tenantId, UUID id) {
        requireTenantId(tenantId);
        Objects.requireNonNull(id, "id is required");
        return repository.findByTenantIdAndId(tenantId, id).map(IntegrationProfileJpaEntity::toDomain);
    }

    @Override
    public List<IntegrationProfile> findAll(UUID tenantId, boolean activeOnly) {
        requireTenantId(tenantId);
        List<IntegrationProfileJpaEntity> entities = activeOnly
            ? repository.findByTenantIdAndActiveTrueOrderByCreatedAtAsc(tenantId)
            : repository.findByTenantIdOrderByCreatedAtAsc(tenantId);
        return entities.stream().map(IntegrationProfileJpaEntity::toDomain).toList();
    }

    @Override
    public boolean existsActive(UUID tenantId, String businessDomain, String externalSource) {
        requireTenantId(tenantId);
        Objects.requireNonNull(businessDomain, "businessDomain is required");
        Objects.requireNonNull(externalSource, "externalSource is required");
        return repository.existsByTenantIdAndBusinessDomainAndExternalSourceAndActiveTrue(
            tenantId, businessDomain, externalSource);
    }

    private void requireTenantId(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId is required");
    }
}
