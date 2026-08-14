package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
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
        this.createdAt = profile.createdAt();
        this.updatedAt = profile.updatedAt();
    }

    static IntegrationProfileJpaEntity from(IntegrationProfile profile) {
        return new IntegrationProfileJpaEntity(profile);
    }

    void apply(IntegrationProfile profile) {
        if (!tenantId.equals(profile.tenantId()) || !id.equals(profile.id())) {
            throw new IllegalArgumentException("Profile identity cannot change");
        }
        if (profile.version() != version && profile.version() != version + 1) {
            throw new IntegrationProfileConflictException("Integration profile version is stale");
        }
        this.businessDomain = profile.businessDomain();
        this.externalSource = profile.externalSource();
        this.direction = profile.direction();
        this.sourceOfTruth = profile.sourceOfTruth();
        this.active = profile.active();
        this.updatedAt = profile.updatedAt();
    }

    IntegrationProfile toDomain() {
        IntegrationProfile profile = IntegrationProfile.create(
                id, tenantId, businessDomain, externalSource, direction, sourceOfTruth);
        long updatesBeforeDeactivation = active ? version : version - 1;
        for (long currentVersion = 0; currentVersion < updatesBeforeDeactivation; currentVersion++) {
            profile = profile.update(
                    businessDomain, externalSource, direction, sourceOfTruth, profile.version());
        }
        return active ? profile : profile.deactivate();
    }
}
