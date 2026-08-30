package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowVersionState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataFlowVersionRepository extends Repository<FlowVersionJpaEntity, UUID> {

    List<FlowVersionJpaEntity> findAllByTenantIdAndFlowIdOrderByVersionNumberDesc(UUID tenantId, UUID flowId);

    Optional<FlowVersionJpaEntity> findByTenantIdAndFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber);

    Optional<FlowVersionJpaEntity> findByTenantIdAndFlowIdAndState(UUID tenantId, UUID flowId, FlowVersionState state);

    int countByTenantIdAndFlowId(UUID tenantId, UUID flowId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FlowVersionJpaEntity v set v.state = :state where v.tenantId = :tenantId and v.id = :id")
    void updateState(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("state") FlowVersionState state);
}
