package com.cl2.integration.integration.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaOutboxPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this(kafkaTemplate, new ObjectMapper());
    }

    @Autowired
    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<?> publish(OutboxJpaEntity event) {
        OutboxEvent domainEvent = event.toDomain();
        String topic = event.getTopic() != null && !event.getTopic().isBlank() ? event.getTopic() : "integration.events";
        ProducerRecord<String, String> record = new ProducerRecord<>(
            topic,
            event.getId().toString(),
            domainEvent.payload()
        );
        record.headers().add(new RecordHeader("X-Tenant-ID", domainEvent.tenantId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Event-Type", domainEvent.eventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Aggregate-ID", domainEvent.aggregateId().toString().getBytes(StandardCharsets.UTF_8)));
        if (domainEvent.aggregateType() != null) {
            record.headers().add(new RecordHeader("X-Business-Domain", domainEvent.aggregateType().getBytes(StandardCharsets.UTF_8)));
        }
        if (domainEvent.eventType().endsWith(".batch.upserted")) {
            int batchSize = extractBatchSize(domainEvent.payload());
            record.headers().add(new RecordHeader("X-Batch-Mode", "true".getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("X-Batch-Size", Integer.toString(batchSize).getBytes(StandardCharsets.UTF_8)));
        }

        return kafkaTemplate.send(record);
    }

    private int extractBatchSize(String payload) {
        try {
            JsonNode batchPayload = objectMapper.readTree(payload);
            if (!batchPayload.isArray()) {
                throw new IllegalArgumentException("Batch event payload must be a JSON array");
            }
            return batchPayload.size();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Batch event payload must be a JSON array", exception);
        }
    }
}
