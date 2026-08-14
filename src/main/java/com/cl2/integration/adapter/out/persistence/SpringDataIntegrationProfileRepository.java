package com.cl2.integration.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataIntegrationProfileRepository extends JpaRepository<IntegrationProfileJpaEntity, UUID> {

    Optional<IntegrationProfileJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<IntegrationProfileJpaEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<IntegrationProfileJpaEntity> findAllByTenantIdAndActiveTrueOrderByCreatedAtDesc(UUID tenantId);

    boolean existsByTenantIdAndBusinessDomainAndExternalSourceAndActiveTrue(
            UUID tenantId, String businessDomain, String externalSource);
}
