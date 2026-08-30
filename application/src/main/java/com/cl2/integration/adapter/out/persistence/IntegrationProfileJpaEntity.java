package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "integration_profile")
class IntegrationProfileJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "business_domain", nullable = false, length = 100)
    private String businessDomain;

    @Column(name = "external_source", nullable = false, length = 100)
    private String externalSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_direction", nullable = false, length = 20)
    private SyncDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_of_truth", nullable = false, length = 20)
    private SourceOfTruth sourceOfTruth;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", length = 20)
    private IntegrationProtocol protocol;

    @Column(name = "connector", length = 100)
    private String connector;

    @Column(name = "adapter", length = 100)
    private String adapter;

    @Column(name = "endpoint", length = 500)
    private String endpoint;

    @Column(name = "credential_ref", length = 255)
    private String credentialRef;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "mapping_json", columnDefinition = "JSON")
    private String mappingJson;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "transformation_json", columnDefinition = "JSON")
    private String transformationJson;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "sync_policy_json", columnDefinition = "JSON")
    private String syncPolicyJson;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "retry_policy_json", columnDefinition = "JSON")
    private String retryPolicyJson;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "rate_limit_policy_json", columnDefinition = "JSON")
    private String rateLimitPolicyJson;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "extraction_config_json", columnDefinition = "JSON")
    private String extractionConfigJson;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private boolean paused;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant updatedAt;

    protected IntegrationProfileJpaEntity() {
    }

    private IntegrationProfileJpaEntity(IntegrationProfile profile) {
        this.id = profile.id();
        this.tenantId = profile.tenantId();
        this.businessDomain = profile.businessDomain();
        this.externalSource = profile.externalSource();
        this.direction = profile.direction();
        this.sourceOfTruth = profile.sourceOfTruth();
        if (profile.configuration() != null) {
            IntegrationProfileConfiguration config = profile.configuration();
            this.protocol = config.protocol();
            this.connector = config.connector();
            this.adapter = config.adapter();
            this.endpoint = config.endpoint();
            this.credentialRef = config.credentialRef();
            this.mappingJson = config.mapping();
            this.transformationJson = config.transformation();
            this.syncPolicyJson = config.syncPolicy();
            this.retryPolicyJson = config.retryPolicy();
            this.rateLimitPolicyJson = config.rateLimitPolicy();
            this.extractionConfigJson = config.extractionConfig();
        }
        this.active = profile.active();
        this.paused = profile.paused();
        this.version = profile.version();
        this.createdAt = toMysqlTimestamp(profile.createdAt());
        this.updatedAt = toMysqlTimestamp(profile.updatedAt());
    }

    static IntegrationProfileJpaEntity from(IntegrationProfile profile) {
        return new IntegrationProfileJpaEntity(profile);
    }

    IntegrationProfile toDomain() {
        IntegrationProfileConfiguration config = null;
        if (protocol != null || connector != null || adapter != null || endpoint != null
                || credentialRef != null || mappingJson != null || transformationJson != null
                || syncPolicyJson != null || retryPolicyJson != null || rateLimitPolicyJson != null
                || extractionConfigJson != null) {
            config = new IntegrationProfileConfiguration(
                    protocol, connector, adapter, endpoint, credentialRef,
                    mappingJson, transformationJson, syncPolicyJson, retryPolicyJson, rateLimitPolicyJson,
                    extractionConfigJson
            );
        }
        return IntegrationProfile.rehydrate(
                id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                config, active, paused, createdAt, updatedAt, version);
    }

    private static Instant toMysqlTimestamp(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
