package com.cl2.integration.domain.port;

import com.cl2.integration.domain.model.Flow;
import java.util.List;
import java.util.UUID;

public interface FlowRepository {

    Flow save(UUID tenantId, Flow flow);

    Flow findById(UUID tenantId, UUID id);

    List<Flow> findAll(UUID tenantId, boolean activeOnly);

    boolean existsActive(UUID tenantId, String code);
}
