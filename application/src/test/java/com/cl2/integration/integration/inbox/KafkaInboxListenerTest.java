package com.cl2.integration.integration.inbox;

import com.cl2.integration.integration.outbound.OutboundEventDispatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaInboxListenerTest {

    @Mock
    private InboxProcessor inboxProcessor;

    @Mock
    private OutboundEventDispatcher outboundEventDispatcher;

    @Captor
    private ArgumentCaptor<Consumer<String>> consumerCaptor;

    private KafkaInboxListener listener;

    @BeforeEach
    void setUp() {
        listener = new KafkaInboxListener(inboxProcessor, outboundEventDispatcher);
    }

    @Test
    @DisplayName("Should extract headers, invoke inboxProcessor and forward to outboundEventDispatcher with external source")
    void shouldExtractHeadersAndDispatch() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String eventType = "CustomerCreatedEvent";
        String payload = "{\"name\":\"Alice\"}";
        String topic = "integration.customers.events";
        String externalSource = "sigo";

        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("X-Tenant-ID", tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("X-Event-Type", eventType.getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("X-External-Source", externalSource.getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                topic, 0, 0L, 0L, null, 0, 0,
                eventId.toString(), payload, headers, null
        );

        listener.onMessage(record);

        verify(inboxProcessor).process(
                eq(eventId),
                eq(tenantId),
                eq(eventType),
                eq(payload),
                eq(topic),
                consumerCaptor.capture()
        );

        Consumer<String> capturedHandler = consumerCaptor.getValue();
        capturedHandler.accept(payload);

        verify(outboundEventDispatcher).dispatch(eventId, tenantId, eventType, payload, externalSource);
    }

    @Test
    @DisplayName("Should handle missing or invalid headers gracefully with defaults")
    void shouldHandleMissingHeadersWithDefaults() {
        String invalidKey = "not-a-uuid";
        String payload = "{\"data\":\"test\"}";
        String topic = "integration.events";

        RecordHeaders headers = new RecordHeaders();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                topic, 0, 0L, 0L, null, 0, 0,
                invalidKey, payload, headers, null
        );

        ArgumentCaptor<UUID> eventIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> tenantIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);

        listener.onMessage(record);

        verify(inboxProcessor).process(
                eventIdCaptor.capture(),
                tenantIdCaptor.capture(),
                eventTypeCaptor.capture(),
                eq(payload),
                eq(topic),
                consumerCaptor.capture()
        );

        assertThat(eventIdCaptor.getValue()).isNotNull();
        assertThat(tenantIdCaptor.getValue()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        assertThat(eventTypeCaptor.getValue()).isEqualTo("UnknownEvent");

        Consumer<String> capturedHandler = consumerCaptor.getValue();
        capturedHandler.accept(payload);

        verify(outboundEventDispatcher).dispatch(
                eventIdCaptor.getValue(),
                tenantIdCaptor.getValue(),
                eventTypeCaptor.getValue(),
                payload,
                null
        );
    }
}
