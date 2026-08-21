package com.cl2.integration.integration.sync;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SyncStateRecorder {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final SyncStateRepository syncStateRepository;

    public SyncStateRecorder(SyncStateRepository syncStateRepository) {
        this.syncStateRepository = syncStateRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID profileId, Instant startedAt, String errorMessage) {
        Instant existingWatermark = syncStateRepository.find(profileId)
                .map(SyncState::lastWatermark)
                .orElse(null);
        String truncatedError = errorMessage == null
                ? null
                : errorMessage.substring(0, Math.min(errorMessage.length(), MAX_ERROR_LENGTH));
        syncStateRepository.upsert(new SyncState(profileId, existingWatermark, startedAt, SyncRunStatus.FAILED, truncatedError));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCancelled(UUID profileId, Instant startedAt, String reason) {
        Instant existingWatermark = syncStateRepository.find(profileId)
                .map(SyncState::lastWatermark)
                .orElse(null);
        String truncatedReason = reason == null
                ? null
                : reason.substring(0, Math.min(reason.length(), MAX_ERROR_LENGTH));
        syncStateRepository.upsert(new SyncState(profileId, existingWatermark, startedAt, SyncRunStatus.CANCELLED, truncatedReason));
    }
}
