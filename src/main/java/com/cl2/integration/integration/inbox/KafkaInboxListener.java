package com.cl2.integration.integration.inbox;

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

    public KafkaInboxListener(InboxProcessor inboxProcessor) {
        this.inboxProcessor = inboxProcessor;
    }

    @KafkaListener(topics = "${integration.inbox.topics:integration.events}", groupId = "${spring.kafka.consumer.group-id:integration-consumer-group}", autoStartup = "${integration.inbox.listener.auto-startup:false}")
    public void onMessage(ConsumerRecord<String, String> record) {
        UUID eventId;
        try {
            eventId = UUID.fromString(record.key());
        } catch (Exception ex) {
            eventId = UUID.randomUUID();
        }
        final UUID finalEventId = eventId;

        UUID tenantId = extractHeaderAsUuid(record, "X-Tenant-ID");
        if (tenantId == null) {
            tenantId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        }

        String eventType = extractHeaderAsString(record, "X-Event-Type", "UnknownEvent");
        log.info("Received event in KafkaInboxListener: eventId={}, tenantId={}, topic={}", eventId, tenantId, record.topic());

        inboxProcessor.process(eventId, tenantId, eventType, record.value(), record.topic(), payload -> {
            log.debug("Dispatched payload to domain handler for eventId={}", finalEventId);
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
