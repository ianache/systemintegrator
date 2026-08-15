package com.cl2.integration.integration.profile;

import com.cl2.integration.application.IntegrationProfileView;
import java.time.Instant;
import java.util.UUID;

public record IntegrationProfileEvent(
        UUID eventId,
        String eventType,
        UUID profileId,
        UUID tenantId,
        Instant occurredAt,
        IntegrationProfileView state) {
}
