package com.cl2.integration.integration.inbox;

import com.cl2.integration.integration.outbound.OutboundEventDispatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class KafkaInboxListener {
    private static final Logger log = LoggerFactory.getLogger(KafkaInboxListener.class);

    private final InboxProcessor inboxProcessor;
    private final OutboundEventDispatcher outboundEventDispatcher;

    public KafkaInboxListener(InboxProcessor inboxProcessor, OutboundEventDispatcher outboundEventDispatcher) {
        this.inboxProcessor = inboxProcessor;
        this.outboundEventDispatcher = outboundEventDispatcher;
    }

    @KafkaListener(topicPattern = "${integration.inbox.topic-pattern:integration\\..*\\.events}", groupId = "${spring.kafka.consumer.group-id:integration-consumer-group}", autoStartup = "${integration.inbox.listener.auto-startup:false}")
    public void onMessage(ConsumerRecord<String, String> record) {
        UUID eventId;
        try {
            eventId = UUID.fromString(record.key());
        } catch (Exception ex) {
            eventId = UUID.randomUUID();
        }
        final UUID finalEventId = eventId;

        UUID rawTenantId = extractHeaderAsUuid(record, "X-Tenant-ID");
        final UUID tenantId = rawTenantId != null ? rawTenantId : UUID.fromString("00000000-0000-0000-0000-000000000000");

        String eventType = extractHeaderAsString(record, "X-Event-Type", "UnknownEvent");
        String externalSource = extractHeaderAsString(record, "X-External-Source", null);
        log.info("Received event in KafkaInboxListener: eventId={}, tenantId={}, topic={}, source={}", eventId, tenantId, record.topic(), externalSource);

        inboxProcessor.process(eventId, tenantId, eventType, record.value(), record.topic(), payload -> {
            outboundEventDispatcher.dispatch(finalEventId, tenantId, eventType, payload, externalSource);
        });
    }

    private UUID extractHeaderAsUuid(ConsumerRecord<String, String> record, String headerKey) {
        Header header = record.headers().lastHeader(headerKey);
        if (header != null && header.value() != null) {
            try {
                return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String extractHeaderAsString(ConsumerRecord<String, String> record, String headerKey, String defaultValue) {
        Header header = record.headers().lastHeader(headerKey);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return defaultValue;
    }
}
