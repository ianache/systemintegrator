package com.cl2.integration.integration.inbox;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class InboxEntityTest {
    @Test
    void shouldCreateAndManageInboxEntityLifecycle() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        InboxJpaEntity entity = new InboxJpaEntity(eventId, tenantId, "VehicleCreated", "{\"vin\":\"123\"}", "RECEIVED", 0, Instant.now());

        assertThat(entity.getStatus()).isEqualTo("RECEIVED");
        assertThat(entity.getPayload()).isEqualTo("{\"vin\":\"123\"}");

        entity.markProcessed(Instant.now());
        assertThat(entity.getStatus()).isEqualTo("PROCESSED");

        entity.markDeadLetter("Error payload");
        assertThat(entity.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(entity.getAttempts()).isEqualTo(1);
        assertThat(entity.getLastError()).isEqualTo("Error payload");
    }
}
