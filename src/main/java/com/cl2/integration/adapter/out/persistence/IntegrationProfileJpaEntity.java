package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.IntegrationProfile;
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

    @Column(nullable = false)
    private boolean active;

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
        this.active = profile.active();
        this.version = profile.version();
        this.createdAt = toMysqlTimestamp(profile.createdAt());
        this.updatedAt = toMysqlTimestamp(profile.updatedAt());
    }

    static IntegrationProfileJpaEntity from(IntegrationProfile profile) {
        return new IntegrationProfileJpaEntity(profile);
    }

    IntegrationProfile toDomain() {
        return IntegrationProfile.rehydrate(
                id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                active, createdAt, updatedAt, version);
    }

    private static Instant toMysqlTimestamp(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
