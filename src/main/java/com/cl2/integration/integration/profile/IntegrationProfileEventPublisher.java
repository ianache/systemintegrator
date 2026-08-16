package com.cl2.integration.integration.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IntegrationProfileEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public IntegrationProfileEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${integration-profile.events:integration-profile.events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publish(IntegrationProfileEvent event) {
        try {
            String payload = objectMapper.writer()
                    .without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(event);
            CompletableFuture<?> future = kafkaTemplate.send(topic, event.profileId().toString(), payload);
            if (future != null) {
                future.join();
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize integration profile event", exception);
        }
    }
}
