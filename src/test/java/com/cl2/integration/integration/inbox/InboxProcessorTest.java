package com.cl2.integration.integration.inbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InboxProcessorTest {
    private InboxStore store;
    private DeadLetterQueuePublisher dlq;
    private InboxProcessor processor;

    @BeforeEach
    void setup() {
        store = mock(InboxStore.class);
        dlq = mock(DeadLetterQueuePublisher.class);
        processor = new InboxProcessor(store, dlq);
    }

    @Test
    void shouldProcessNewEventSuccessfully() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(store.recordIfAbsent(eventId, tenantId, "VehicleCreated", "{}")).thenReturn(true);

        boolean processed = processor.process(eventId, tenantId, "VehicleCreated", "{}", "integration.events", payload -> {});

        assertThat(processed).isTrue();
        verify(store).markProcessed(eventId, tenantId);
        verifyNoInteractions(dlq);
    }

    @Test
    void shouldSkipDuplicateAlreadyProcessedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(store.recordIfAbsent(eventId, tenantId, "VehicleCreated", "{}")).thenReturn(false);
        InboxJpaEntity existing = new InboxJpaEntity(eventId, tenantId, "VehicleCreated", "{}", "PROCESSED", 1, Instant.now());
        when(store.find(eventId, tenantId)).thenReturn(Optional.of(existing));

        boolean processed = processor.process(eventId, tenantId, "VehicleCreated", "{}", "integration.events", payload -> {});

        assertThat(processed).isFalse();
        verify(store, never()).markProcessed(any(), any());
        verifyNoInteractions(dlq);
    }

    @Test
    void shouldForwardToDlqOnDomainFailure() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(store.recordIfAbsent(eventId, tenantId, "VehicleCreated", "{}")).thenReturn(true);

        assertThatThrownBy(() -> processor.process(eventId, tenantId, "VehicleCreated", "{}", "integration.events", payload -> {
            throw new IllegalArgumentException("Invalid payload");
        })).isInstanceOf(RuntimeException.class);

        verify(store).markDeadLetter(eq(eventId), eq(tenantId), contains("Invalid payload"));
        verify(dlq).publishToDlq(eq("integration.events"), eq(eventId), eq(tenantId), eq("{}"), contains("Invalid payload"));
    }
}
