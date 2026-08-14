package com.cl2.integration.domain.port;

import com.cl2.integration.domain.model.IntegrationProfile;
import java.util.List;
import java.util.UUID;

public interface IntegrationProfileRepository {

    IntegrationProfile save(IntegrationProfile profile);

    IntegrationProfile findById(UUID tenantId, UUID id);

    List<IntegrationProfile> findAll(UUID tenantId, boolean activeOnly);

    boolean existsActive(UUID tenantId, String businessDomain, String externalSource);
}
