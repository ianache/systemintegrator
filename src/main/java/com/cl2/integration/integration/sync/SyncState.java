package com.cl2.integration.integration.sync;

import java.time.Instant;
import java.util.UUID;

public record SyncState(
        UUID profileId,
        Instant lastWatermark,
        Instant lastRunStartedAt,
        SyncRunStatus lastRunStatus,
        String lastError
) {}
