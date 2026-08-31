package com.cl2.integration.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface SpringDataFlowExecutionStepRepository extends Repository<FlowExecutionStepJpaEntity, UUID> {

    FlowExecutionStepJpaEntity save(FlowExecutionStepJpaEntity entity);

    List<FlowExecutionStepJpaEntity> findByFlowExecutionIdOrderByStepOrderAsc(UUID flowExecutionId);
}
