package com.cl2.integration.integration.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaOutboxPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<?> publish(OutboxJpaEntity event) {
        String topic = event.getTopic() != null && !event.getTopic().isBlank() ? event.getTopic() : "integration.events";
        ProducerRecord<String, String> record = new ProducerRecord<>(
            topic,
            event.getId().toString(),
            event.toDomain().payload()
        );
        record.headers().add(new RecordHeader("X-Tenant-ID", event.toDomain().tenantId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Event-Type", event.toDomain().eventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Aggregate-ID", event.toDomain().aggregateId().toString().getBytes(StandardCharsets.UTF_8)));

        return kafkaTemplate.send(record);
    }
}
