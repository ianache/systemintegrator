package com.cl2.integration.integration.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "integration_sync_state")
class SyncStateJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(name = "profile_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID profileId;

    @Column(name = "last_watermark", columnDefinition = "TIMESTAMP(6)")
    private Instant lastWatermark;

    @Column(name = "last_run_started_at", columnDefinition = "TIMESTAMP(6)")
    private Instant lastRunStartedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 20)
    private SyncRunStatus lastRunStatus;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected SyncStateJpaEntity() {
    }

    static SyncStateJpaEntity from(SyncState state) {
        SyncStateJpaEntity entity = new SyncStateJpaEntity();
        entity.applyUpdate(state);
        return entity;
    }

    void applyUpdate(SyncState state) {
        this.profileId = state.profileId();
        this.lastWatermark = state.lastWatermark();
        this.lastRunStartedAt = state.lastRunStartedAt();
        this.lastRunStatus = state.lastRunStatus();
        this.lastError = state.lastError();
    }

    SyncState toDomain() {
        return new SyncState(profileId, lastWatermark, lastRunStartedAt, lastRunStatus, lastError);
    }
}
