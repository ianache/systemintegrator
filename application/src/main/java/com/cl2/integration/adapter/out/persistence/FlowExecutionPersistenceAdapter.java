package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.FlowMetricsSummary;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStatus;
import com.cl2.integration.domain.model.FlowExecutionStep;
import com.cl2.integration.domain.port.FlowExecutionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class FlowExecutionPersistenceAdapter implements FlowExecutionRepository {

    private final SpringDataFlowExecutionRepository repository;
    private final SpringDataFlowExecutionStepRepository stepRepository;
    private final EntityManager entityManager;

    FlowExecutionPersistenceAdapter(SpringDataFlowExecutionRepository repository,
            SpringDataFlowExecutionStepRepository stepRepository, EntityManager entityManager) {
        this.repository = repository;
        this.stepRepository = stepRepository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public FlowExecution save(UUID tenantId, FlowExecution execution) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(execution, "execution must not be null");
        if (!tenantId.equals(execution.tenantId())) {
            throw new IllegalArgumentException("tenantId must match the execution tenantId");
        }
        FlowExecutionJpaEntity entity = FlowExecutionJpaEntity.from(execution);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public void saveSteps(UUID flowExecutionId, List<FlowExecutionStep> steps) {
        for (FlowExecutionStep step : steps) {
            entityManager.persist(FlowExecutionStepJpaEntity.from(step));
        }
        entityManager.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public FlowMetricsSummary executionMetrics(UUID tenantId, Instant since) {
        long total = repository.countByTenantIdAndStartedAtGreaterThanEqual(tenantId, since);
        if (total == 0) {
            return new FlowMetricsSummary(0, 0, 0.0, null);
        }
        long failures = repository.countByTenantIdAndStartedAtGreaterThanEqualAndStatus(
                tenantId, since, FlowExecutionStatus.FAILURE);
        double errorRatePct = 100.0 * failures / total;

        int offset = (int) Math.min(Math.ceil(total * 0.95) - 1, total - 1);
        Long p95 = repository.findDurationAtOffset(tenantId, since, offset);

        return new FlowMetricsSummary(0, total, errorRatePct, p95);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlowExecution> findByFlow(UUID tenantId, UUID flowId) {
        return repository.findByTenantIdAndFlowIdOrderByStartedAtDesc(tenantId, flowId).stream()
                .map(FlowExecutionJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FlowExecution> findById(UUID tenantId, UUID flowId, UUID executionId) {
        return repository.findByTenantIdAndFlowIdAndId(tenantId, flowId, executionId)
                .map(FlowExecutionJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlowExecutionStep> findSteps(UUID flowExecutionId) {
        return stepRepository.findByFlowExecutionIdOrderByStepOrderAsc(flowExecutionId).stream()
                .map(FlowExecutionStepJpaEntity::toDomain)
                .toList();
    }
}
