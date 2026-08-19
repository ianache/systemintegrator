package com.cl2.integration.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataIntegrationProfileRepository extends Repository<IntegrationProfileJpaEntity, UUID> {

    Optional<IntegrationProfileJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<IntegrationProfileJpaEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<IntegrationProfileJpaEntity> findAllByTenantIdAndActiveTrueOrderByCreatedAtDesc(UUID tenantId);

    boolean existsByTenantIdAndBusinessDomainAndExternalSourceAndActiveTrue(
            UUID tenantId, String businessDomain, String externalSource);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntegrationProfileJpaEntity profile
            set profile.businessDomain = :businessDomain,
                profile.externalSource = :externalSource,
                profile.direction = :direction,
                profile.sourceOfTruth = :sourceOfTruth,
                profile.protocol = :protocol,
                profile.connector = :connector,
                profile.adapter = :adapter,
                profile.endpoint = :endpoint,
                profile.credentialRef = :credentialRef,
                profile.mappingJson = :mappingJson,
                profile.transformationJson = :transformationJson,
                profile.syncPolicyJson = :syncPolicyJson,
                profile.retryPolicyJson = :retryPolicyJson,
                profile.rateLimitPolicyJson = :rateLimitPolicyJson,
                profile.extractionConfigJson = :extractionConfigJson,
                profile.active = :active,
                profile.updatedAt = :updatedAt,
                profile.version = profile.version + 1
            where profile.tenantId = :tenantId
              and profile.id = :id
              and profile.version = :expectedVersion
            """)
    int updateIfVersionMatches(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("businessDomain") String businessDomain,
            @Param("externalSource") String externalSource,
            @Param("direction") com.cl2.integration.domain.model.SyncDirection direction,
            @Param("sourceOfTruth") com.cl2.integration.domain.model.SourceOfTruth sourceOfTruth,
            @Param("protocol") com.cl2.integration.domain.model.IntegrationProtocol protocol,
            @Param("connector") String connector,
            @Param("adapter") String adapter,
            @Param("endpoint") String endpoint,
            @Param("credentialRef") String credentialRef,
            @Param("mappingJson") String mappingJson,
            @Param("transformationJson") String transformationJson,
            @Param("syncPolicyJson") String syncPolicyJson,
            @Param("retryPolicyJson") String retryPolicyJson,
            @Param("rateLimitPolicyJson") String rateLimitPolicyJson,
            @Param("extractionConfigJson") String extractionConfigJson,
            @Param("active") boolean active,
            @Param("updatedAt") java.time.Instant updatedAt);
}
