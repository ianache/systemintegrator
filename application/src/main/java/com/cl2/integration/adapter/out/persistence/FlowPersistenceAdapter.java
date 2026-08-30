package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.port.FlowRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class FlowPersistenceAdapter implements FlowRepository {

    private final SpringDataFlowRepository repository;
    private final EntityManager entityManager;

    FlowPersistenceAdapter(SpringDataFlowRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public Flow save(UUID tenantId, Flow flow) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(flow, "flow must not be null");
        if (!tenantId.equals(flow.tenantId())) {
            throw new IllegalArgumentException("tenantId must match the flow tenantId");
        }
        try {
            if (flow.version() == 0) {
                FlowJpaEntity entity = FlowJpaEntity.from(flow);
                entityManager.persist(entity);
                entityManager.flush();
                return entity.toDomain();
            }

            int updatedRows = repository.updateIfVersionMatches(
                    flow.tenantId(), flow.id(), flow.version() - 1,
                    flow.name(), flow.triggerSummary(), flow.draftGraph(), flow.activeVersionNumber(),
                    flow.archived(), flow.updatedAt());
            if (updatedRows == 0) {
                throw new FlowConflictException("Flow version is stale");
            }
            // Reload from database
            return repository.findByTenantIdAndId(flow.tenantId(), flow.id())
                    .map(FlowJpaEntity::toDomain)
                    .orElseThrow(() -> new FlowConflictException("Flow not found after update"));
        } catch (DataIntegrityViolationException | ConstraintViolationException exception) {
            throw new FlowConflictException("Flow conflicts with an existing flow");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Flow findById(UUID tenantId, UUID id) {
        return repository.findByTenantIdAndId(tenantId, id)
                .map(FlowJpaEntity::toDomain)
                .orElseThrow(() -> new FlowNotFoundException("Flow " + id + " was not found for tenant " + tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Flow> findAll(UUID tenantId, boolean activeOnly) {
        List<FlowJpaEntity> flows = activeOnly
                ? repository.findAllByTenantIdAndArchivedFalseOrderByCreatedAtDesc(tenantId)
                : repository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        return flows.stream().map(FlowJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActive(UUID tenantId, String code) {
        return repository.existsByTenantIdAndCodeAndArchivedFalse(tenantId, code);
    }
}
