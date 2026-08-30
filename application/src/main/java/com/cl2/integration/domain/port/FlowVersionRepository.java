package com.cl2.integration.domain.port;

import com.cl2.integration.domain.model.FlowVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowVersionRepository {

    FlowVersion save(UUID tenantId, FlowVersion version);

    List<FlowVersion> findAllByFlowId(UUID tenantId, UUID flowId);

    Optional<FlowVersion> findByFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber);

    Optional<FlowVersion> findActiveByFlowId(UUID tenantId, UUID flowId);

    int nextVersionNumber(UUID tenantId, UUID flowId);
}
