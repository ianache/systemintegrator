package com.cl2.integration.domain.model;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class IntegrationProfile {

    private final UUID id;
    private final UUID tenantId;
    private final Instant createdAt;
    private String businessDomain;
    private String externalSource;
    private SyncDirection direction;
    private SourceOfTruth sourceOfTruth;
    private boolean active;
    private long version;
    private Instant updatedAt;

    private IntegrationProfile(
        UUID id,
        UUID tenantId,
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId is required");
        this.businessDomain = requireText(businessDomain, "businessDomain");
        this.externalSource = requireText(externalSource, "externalSource");
        this.direction = Objects.requireNonNull(direction, "direction is required");
        this.sourceOfTruth = Objects.requireNonNull(sourceOfTruth, "sourceOfTruth is required");
        this.active = active;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    public static IntegrationProfile create(
        UUID id,
        UUID tenantId,
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth
    ) {
        Instant now = Instant.now();
        return new IntegrationProfile(
            id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, true, 0, now, now);
    }

    public static IntegrationProfile restore(
        UUID id,
        UUID tenantId,
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new IntegrationProfile(
            id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, active, version, createdAt,
            updatedAt);
    }

    public void update(
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth,
        long expectedVersion
    ) {
        if (version != expectedVersion) {
            throw new IntegrationProfileConflictException("Integration profile version does not match");
        }
        this.businessDomain = requireText(businessDomain, "businessDomain");
        this.externalSource = requireText(externalSource, "externalSource");
        this.direction = Objects.requireNonNull(direction, "direction is required");
        this.sourceOfTruth = Objects.requireNonNull(sourceOfTruth, "sourceOfTruth is required");
        this.version++;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        if (active) {
            active = false;
            version++;
            updatedAt = Instant.now();
        }
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String businessDomain() {
        return businessDomain;
    }

    public String externalSource() {
        return externalSource;
    }

    public SyncDirection direction() {
        return direction;
    }

    public SourceOfTruth sourceOfTruth() {
        return sourceOfTruth;
    }

    public boolean active() {
        return active;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null) {
            throw new NullPointerException(fieldName + " is required");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
