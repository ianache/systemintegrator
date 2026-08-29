package com.cl2.integration.integration.inbox;

import com.cl2.integration.infrastructure.metrics.IntegrationMetrics;
import com.cl2.integration.integration.batch.BatchContext;
import com.cl2.integration.integration.outbound.OutboundEventDispatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class KafkaInboxListener {
    private static final Logger log = LoggerFactory.getLogger(KafkaInboxListener.class);

    private final InboxProcessor inboxProcessor;
    private final OutboundEventDispatcher outboundEventDispatcher;
    private final IntegrationMetrics metrics;

    public KafkaInboxListener(InboxProcessor inboxProcessor, OutboundEventDispatcher outboundEventDispatcher) {
        this(inboxProcessor, outboundEventDispatcher, null);
    }

    @Autowired
    public KafkaInboxListener(InboxProcessor inboxProcessor, OutboundEventDispatcher outboundEventDispatcher, @Autowired(required = false) IntegrationMetrics metrics) {
        this.inboxProcessor = inboxProcessor;
        this.outboundEventDispatcher = outboundEventDispatcher;
        this.metrics = metrics;
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
        BatchContext batchContext = extractBatchContext(record);
        log.info("Received event in KafkaInboxListener: eventId={}, tenantId={}, topic={}, source={}", eventId, tenantId, record.topic(), externalSource);

        if (metrics != null) {
            String derivedDomain = outboundEventDispatcher.deriveBusinessDomain(eventType);
            metrics.recordInboxMessageConsumed(tenantId.toString(), derivedDomain, record.topic());
        }

        inboxProcessor.process(eventId, tenantId, eventType, record.value(), record.topic(), payload -> {
            outboundEventDispatcher.dispatch(finalEventId, tenantId, eventType, payload, externalSource, batchContext);
        });
    }

    private BatchContext extractBatchContext(ConsumerRecord<String, String> record) {
        String batchMode = extractHeaderAsString(record, "X-Batch-Mode", null);
        String batchSize = extractHeaderAsString(record, "X-Batch-Size", null);
        if (!"true".equalsIgnoreCase(batchMode) || batchSize == null) {
            return BatchContext.unitary();
        }

        try {
            int parsedBatchSize = Integer.parseInt(batchSize);
            return parsedBatchSize > 0 ? BatchContext.batch(parsedBatchSize) : BatchContext.unitary();
        } catch (NumberFormatException exception) {
            return BatchContext.unitary();
        }
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
