package com.cl2.integration.integration.outbox;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOutboxPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafkaTemplate) { this.kafkaTemplate = kafkaTemplate; }
    public void publish(OutboxEvent event) {
        kafkaTemplate.send("integration.events", event.id().toString(), event.payload());
    }
}
