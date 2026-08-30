package com.cl2.integration.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataFlowRepository extends Repository<FlowJpaEntity, UUID> {

    Optional<FlowJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<FlowJpaEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<FlowJpaEntity> findAllByTenantIdAndArchivedFalseOrderByCreatedAtDesc(UUID tenantId);

    boolean existsByTenantIdAndCodeAndArchivedFalse(UUID tenantId, String code);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FlowJpaEntity flow
            set flow.name = :name,
                flow.triggerSummary = :triggerSummary,
                flow.draftGraph = :draftGraph,
                flow.activeVersionNumber = :activeVersionNumber,
                flow.archived = :archived,
                flow.updatedAt = :updatedAt,
                flow.version = flow.version + 1
            where flow.tenantId = :tenantId
              and flow.id = :id
              and flow.version = :expectedVersion
            """)
    int updateIfVersionMatches(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("name") String name,
            @Param("triggerSummary") String triggerSummary,
            @Param("draftGraph") String draftGraph,
            @Param("activeVersionNumber") Integer activeVersionNumber,
            @Param("archived") boolean archived,
            @Param("updatedAt") Instant updatedAt);
}
