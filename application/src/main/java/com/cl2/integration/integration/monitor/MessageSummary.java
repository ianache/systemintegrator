package com.cl2.integration.integration.monitor;

import java.time.Instant;
import java.util.UUID;

public record MessageSummary(
        UUID id,
        String direction,
        String eventType,
        String domain,
        String status,
        int attempts,
        String lastError,
        Instant timestamp
) {
}
