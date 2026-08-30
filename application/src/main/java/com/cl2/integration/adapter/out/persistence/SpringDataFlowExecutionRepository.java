package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataFlowExecutionRepository extends Repository<FlowExecutionJpaEntity, UUID> {

    FlowExecutionJpaEntity save(FlowExecutionJpaEntity entity);

    long countByTenantIdAndStartedAtGreaterThanEqual(UUID tenantId, Instant since);

    long countByTenantIdAndStartedAtGreaterThanEqualAndStatus(UUID tenantId, Instant since,
                                                               FlowExecutionStatus status);

    @Query(value = """
            select duration_ms from flow_execution
            where tenant_id = :tenantId and started_at >= :since
            order by duration_ms asc
            limit 1 offset :offset
            """, nativeQuery = true)
    Long findDurationAtOffset(@Param("tenantId") UUID tenantId, @Param("since") Instant since,
                              @Param("offset") int offset);
}
