package com.cl2.integration.integration.lookup.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataValueLookupRepository extends JpaRepository<ValueLookupJpaEntity, UUID> {

    @Query("SELECT v.targetValue FROM ValueLookupJpaEntity v WHERE v.tenantId = :tenantId AND v.externalSource = :externalSource AND v.catalogCode = :catalogCode AND v.sourceValue = :sourceValue AND v.active = true")
    Optional<String> findTargetValue(
            @Param("tenantId") UUID tenantId,
            @Param("externalSource") String externalSource,
            @Param("catalogCode") String catalogCode,
            @Param("sourceValue") String sourceValue
    );

    List<ValueLookupJpaEntity> findAllByTenantIdAndExternalSourceAndCatalogCode(
            UUID tenantId,
            String externalSource,
            String catalogCode
    );

    @Modifying
    @Query("DELETE FROM ValueLookupJpaEntity v WHERE v.tenantId = :tenantId AND v.id = :id")
    void deleteByTenantIdAndId(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
