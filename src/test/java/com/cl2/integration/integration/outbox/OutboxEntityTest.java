package com.cl2.integration.integration.outbox;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxEntityTest {
    @Test
    void shouldCreateAndConvertOutboxEventWithTopic() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(id, tenantId, aggregateId, "Vehicle", "vehicle.created", "integration.events", "{\"vin\":\"123\"}", "PENDING", 0, Instant.now(), null, null, Instant.now());
        
        OutboxJpaEntity entity = OutboxJpaEntity.from(event);
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getTopic()).isEqualTo("integration.events");
    }
}
