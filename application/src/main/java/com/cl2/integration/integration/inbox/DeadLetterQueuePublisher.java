package com.cl2.integration.integration.inbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class DeadLetterQueuePublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;

    public DeadLetterQueuePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishToDlq(String originalTopic, UUID eventId, UUID tenantId, String payload, String errorMessage) {
        String dlqTopic = (originalTopic != null ? originalTopic : "integration.events") + ".dlq";
        ProducerRecord<String, String> record = new ProducerRecord<>(dlqTopic, eventId.toString(), payload != null ? payload : "{}");
        if (tenantId != null) {
            record.headers().add(new RecordHeader("X-Tenant-ID", tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        }
        if (errorMessage != null) {
            record.headers().add(new RecordHeader("X-Error-Message", errorMessage.getBytes(StandardCharsets.UTF_8)));
        }
        kafkaTemplate.send(record);
    }
}
