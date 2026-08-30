package com.cl2.integration.domain.model;

import com.cl2.integration.application.exception.FlowConflictException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class Flow {

    private final UUID id;
    private final UUID tenantId;
    private final String code;
    private final String name;
    private final String draftGraph;
    private final String triggerSummary;
    private final Integer activeVersionNumber;
    private final boolean archived;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private Flow(UUID id, UUID tenantId, String code, String name, String draftGraph, String triggerSummary,
                 Integer activeVersionNumber, boolean archived, Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.code = requireNonBlank(code, "code");
        this.name = requireNonBlank(name, "name");
        this.draftGraph = draftGraph;
        this.triggerSummary = triggerSummary;
        this.activeVersionNumber = activeVersionNumber;
        this.archived = archived;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Flow create(UUID id, UUID tenantId, String code, String name) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new Flow(id, tenantId, code, name, null, null, null, false, now, now, 0);
    }

    public static Flow rehydrate(UUID id, UUID tenantId, String code, String name, String draftGraph,
                                  String triggerSummary, Integer activeVersionNumber, boolean archived,
                                  Instant createdAt, Instant updatedAt, long version) {
        return new Flow(id, tenantId, code, name, draftGraph, triggerSummary, activeVersionNumber, archived,
                createdAt, updatedAt, version);
    }

    public Flow updateDraft(String name, String triggerSummary, String draftGraph, long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        return new Flow(id, tenantId, code, name, draftGraph, triggerSummary, activeVersionNumber, archived,
                createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public Flow withActiveVersion(int versionNumber) {
        return new Flow(id, tenantId, code, name, draftGraph, triggerSummary, versionNumber, archived,
                createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public Flow archive() {
        if (archived) {
            return this;
        }
        return new Flow(id, tenantId, code, name, draftGraph, triggerSummary, activeVersionNumber, true,
                createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public FlowStatus status() {
        if (archived) {
            return FlowStatus.OBSOLETE;
        }
        return activeVersionNumber == null ? FlowStatus.DRAFT : FlowStatus.PUBLISHED;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String draftGraph() {
        return draftGraph;
    }

    public String triggerSummary() {
        return triggerSummary;
    }

    public Integer activeVersionNumber() {
        return activeVersionNumber;
    }

    public boolean archived() {
        return archived;
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
            throw new FlowConflictException("Flow version does not match expected version");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
