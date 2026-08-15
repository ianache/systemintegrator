package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class IntegrationProfilePersistenceAdapter implements IntegrationProfileRepository {

    private final SpringDataIntegrationProfileRepository repository;
    private final EntityManager entityManager;

    IntegrationProfilePersistenceAdapter(SpringDataIntegrationProfileRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public IntegrationProfile save(UUID tenantId, IntegrationProfile profile) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        if (!tenantId.equals(profile.tenantId())) {
            throw new IllegalArgumentException("tenantId must match the profile tenantId");
        }
        try {
            if (profile.version() == 0) {
                IntegrationProfileJpaEntity entity = IntegrationProfileJpaEntity.from(profile);
                entityManager.persist(entity);
                entityManager.flush();
                return entity.toDomain();
            }

            int updatedRows = repository.updateIfVersionMatches(
                    profile.tenantId(), profile.id(), profile.version() - 1,
                    profile.businessDomain(), profile.externalSource(), profile.direction(), profile.sourceOfTruth(),
                    profile.active(), profile.updatedAt());
            if (updatedRows == 0) {
                throw new IntegrationProfileConflictException("Integration profile version is stale");
            }
            return IntegrationProfileJpaEntity.from(profile).toDomain();
        } catch (DataIntegrityViolationException | ConstraintViolationException exception) {
            throw new IntegrationProfileConflictException("Integration profile conflicts with an existing profile");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrationProfile findById(UUID tenantId, UUID id) {
        return repository.findByTenantIdAndId(tenantId, id)
                .map(IntegrationProfileJpaEntity::toDomain)
                .orElseThrow(() -> new IntegrationProfileNotFoundException(
                        "Integration profile " + id + " was not found for tenant " + tenantId));
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
