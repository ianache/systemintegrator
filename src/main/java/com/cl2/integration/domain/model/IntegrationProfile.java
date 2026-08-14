package com.cl2.integration.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class IntegrationProfile {

    private final UUID id;
    private final UUID tenantId;
    private final String businessDomain;
    private final String externalSource;
    private final SyncDirection direction;
    private final SourceOfTruth sourceOfTruth;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private IntegrationProfile(UUID id, UUID tenantId, String businessDomain, String externalSource,
                               SyncDirection direction, SourceOfTruth sourceOfTruth, boolean active,
                               Instant createdAt, Instant updatedAt, long version) {
        this.id = requireId(id, "id");
        this.tenantId = requireId(tenantId, "tenantId");
        this.businessDomain = requireNonBlank(businessDomain, "businessDomain");
        this.externalSource = requireNonBlank(externalSource, "externalSource");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.sourceOfTruth = Objects.requireNonNull(sourceOfTruth, "sourceOfTruth must not be null");
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static IntegrationProfile create(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                            SyncDirection direction, SourceOfTruth sourceOfTruth) {
        Instant now = Instant.now();
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                true, now, now, 0);
    }

    public IntegrationProfile update(String businessDomain, String externalSource, SyncDirection direction,
                                     SourceOfTruth sourceOfTruth, long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                active, createdAt, Instant.now(), version + 1);
    }

    public IntegrationProfile deactivate() {
        if (!active) {
            return this;
        }
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                false, createdAt, Instant.now(), version + 1);
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

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new IllegalStateException("Integration profile version does not match expected version");
        }
    }

    private static UUID requireId(UUID id, String fieldName) {
        return Objects.requireNonNull(id, fieldName + " must not be null");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
