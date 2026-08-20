package com.cl2.integration.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

public record TriggerSyncResponse(
        UUID profileId,
        String status,
        Instant triggeredAt
) {
    public static TriggerSyncResponse triggered(UUID profileId) {
        return new TriggerSyncResponse(profileId, "TRIGGERED", Instant.now());
    }
}
