package com.cl2.integration.integration.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class IntegrationProfileEventPublisher {

    private static final String TOPIC = "integration-profile.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public IntegrationProfileEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(IntegrationProfileEvent event) {
        try {
            String payload = objectMapper.writer()
                    .without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(event);
            kafkaTemplate.send(TOPIC, event.profileId().toString(), payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize integration profile event", exception);
        }
    }
}
