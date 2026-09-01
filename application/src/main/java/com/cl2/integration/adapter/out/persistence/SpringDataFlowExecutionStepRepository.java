package com.cl2.integration.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataFlowExecutionStepRepository extends Repository<FlowExecutionStepJpaEntity, UUID> {

    FlowExecutionStepJpaEntity save(FlowExecutionStepJpaEntity entity);

    List<FlowExecutionStepJpaEntity> findByFlowExecutionIdOrderByStepOrderAsc(UUID flowExecutionId);

    long countByFlowExecutionId(UUID flowExecutionId);

    @Query(value = """
            select count(*) from flow_execution_step s
            join flow_execution e on e.id = s.flow_execution_id
            where e.tenant_id = :tenantId and e.started_at >= :since and s.status = 'FAILURE'
            """, nativeQuery = true)
    long countFailedStepsSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);
}
