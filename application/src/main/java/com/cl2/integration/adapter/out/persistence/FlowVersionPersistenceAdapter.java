package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.model.FlowVersionState;
import com.cl2.integration.domain.port.FlowVersionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class FlowVersionPersistenceAdapter implements FlowVersionRepository {

    private final SpringDataFlowVersionRepository repository;
    private final EntityManager entityManager;

    FlowVersionPersistenceAdapter(SpringDataFlowVersionRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public FlowVersion save(UUID tenantId, FlowVersion version) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        if (!tenantId.equals(version.tenantId())) {
            throw new IllegalArgumentException("tenantId must match the version tenantId");
        }
        Optional<FlowVersionJpaEntity> existing = repository.findByTenantIdAndFlowIdAndVersionNumber(
                tenantId, version.flowId(), version.versionNumber());
        if (existing.isPresent()) {
            repository.updateState(tenantId, existing.get().toDomain().id(), version.state());
            return version;
        }
        FlowVersionJpaEntity entity = FlowVersionJpaEntity.from(version);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlowVersion> findAllByFlowId(UUID tenantId, UUID flowId) {
        return repository.findAllByTenantIdAndFlowIdOrderByVersionNumberDesc(tenantId, flowId).stream()
                .map(FlowVersionJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FlowVersion> findByFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber) {
        return repository.findByTenantIdAndFlowIdAndVersionNumber(tenantId, flowId, versionNumber)
                .map(FlowVersionJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FlowVersion> findActiveByFlowId(UUID tenantId, UUID flowId) {
        return repository.findByTenantIdAndFlowIdAndState(tenantId, flowId, FlowVersionState.ACTIVE)
                .map(FlowVersionJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public int nextVersionNumber(UUID tenantId, UUID flowId) {
        return repository.countByTenantIdAndFlowId(tenantId, flowId) + 1;
    }
}
