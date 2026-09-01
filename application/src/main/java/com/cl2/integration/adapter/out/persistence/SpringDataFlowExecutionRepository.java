package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataFlowExecutionRepository extends Repository<FlowExecutionJpaEntity, UUID> {

    FlowExecutionJpaEntity save(FlowExecutionJpaEntity entity);

    List<FlowExecutionJpaEntity> findByTenantIdAndFlowIdOrderByStartedAtDesc(UUID tenantId, UUID flowId);

    Optional<FlowExecutionJpaEntity> findByTenantIdAndFlowIdAndId(UUID tenantId, UUID flowId, UUID id);

    long countByTenantIdAndStartedAtGreaterThanEqual(UUID tenantId, Instant since);

    long countByTenantIdAndStartedAtGreaterThanEqualAndStatus(UUID tenantId, Instant since,
                                                               FlowExecutionStatus status);

    long countByTenantIdAndFlowIdAndStartedAtGreaterThanEqual(UUID tenantId, UUID flowId, Instant since);

    long countByTenantIdAndFlowIdAndStartedAtGreaterThanEqualAndStatus(UUID tenantId, UUID flowId, Instant since,
                                                                        FlowExecutionStatus status);

    Optional<FlowExecutionJpaEntity> findFirstByTenantIdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            UUID tenantId, Instant since);

    @Query(value = """
            select duration_ms from flow_execution
            where tenant_id = :tenantId and started_at >= :since
            order by duration_ms asc
            limit 1 offset :offset
            """, nativeQuery = true)
    Long findDurationAtOffset(@Param("tenantId") UUID tenantId, @Param("since") Instant since,
                              @Param("offset") int offset);

    @Query(value = """
            select duration_ms from flow_execution
            where tenant_id = :tenantId and flow_id = :flowId and started_at >= :since
            order by duration_ms asc
            limit 1 offset :offset
            """, nativeQuery = true)
    Long findDurationAtOffsetForFlow(@Param("tenantId") UUID tenantId, @Param("flowId") UUID flowId,
                                      @Param("since") Instant since, @Param("offset") int offset);
}
