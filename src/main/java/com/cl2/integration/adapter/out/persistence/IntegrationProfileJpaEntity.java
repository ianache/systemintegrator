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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "integration_profile")
class IntegrationProfileJpaEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "business_domain", nullable = false, length = 100)
    private String businessDomain;

    @Column(name = "external_source", nullable = false, length = 100)
    private String externalSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_direction", nullable = false, length = 32)
    private SyncDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_of_truth", nullable = false, length = 32)
    private SourceOfTruth sourceOfTruth;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected IntegrationProfileJpaEntity() {
    }

    private IntegrationProfileJpaEntity(IntegrationProfile profile) {
        id = profile.id();
        tenantId = profile.tenantId();
        businessDomain = profile.businessDomain();
        externalSource = profile.externalSource();
        direction = profile.direction();
        sourceOfTruth = profile.sourceOfTruth();
        active = profile.active();
        createdAt = profile.createdAt();
        updatedAt = profile.updatedAt();
        version = profile.version();
    }

    static IntegrationProfileJpaEntity fromNewProfile(IntegrationProfile profile) {
        return new IntegrationProfileJpaEntity(profile);
    }

    void updateFrom(IntegrationProfile profile) {
        requireSameIdentity(profile);
        businessDomain = profile.businessDomain();
        externalSource = profile.externalSource();
        direction = profile.direction();
        sourceOfTruth = profile.sourceOfTruth();
        active = profile.active();
        updatedAt = profile.updatedAt();
    }

    long version() {
        return version;
    }

    IntegrationProfile toDomain() {
        return IntegrationProfile.restore(
            id,
            tenantId,
            businessDomain,
            externalSource,
            direction,
            sourceOfTruth,
            active,
            version,
            createdAt,
            updatedAt);
    }

    private void requireSameIdentity(IntegrationProfile profile) {
        if (!Objects.equals(id, profile.id()) || !Objects.equals(tenantId, profile.tenantId())) {
            throw new IllegalArgumentException("profile identity cannot change");
        }
    }
}
