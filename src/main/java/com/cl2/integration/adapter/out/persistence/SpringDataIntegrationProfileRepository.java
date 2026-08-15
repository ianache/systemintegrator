package com.cl2.integration.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataIntegrationProfileRepository extends JpaRepository<IntegrationProfileJpaEntity, UUID> {

    Optional<IntegrationProfileJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<IntegrationProfileJpaEntity> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    List<IntegrationProfileJpaEntity> findByTenantIdAndActiveTrueOrderByCreatedAtAsc(UUID tenantId);

    boolean existsByTenantIdAndBusinessDomainAndExternalSourceAndActiveTrue(
        UUID tenantId,
        String businessDomain,
        String externalSource);
}
