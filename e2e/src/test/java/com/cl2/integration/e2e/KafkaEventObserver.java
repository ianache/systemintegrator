package com.cl2.integration.e2e;

import com.cl2.integration.integration.profile.IntegrationProfileEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

final class KafkaEventObserver implements AutoCloseable {

    private static final String GROUP_PREFIX = "integration-profile-e2e-";
    private static final String DEFAULT_TOPIC = "integration-profile.events";

    private final Consumer<String, String> consumer;
    private final ObjectMapper objectMapper;

    KafkaEventObserver(Consumer<String, String> consumer, ObjectMapper objectMapper) {
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.consumer.subscribe(List.of(DEFAULT_TOPIC));
    }

    KafkaEventObserver(String bootstrapServers, String topic, ObjectMapper objectMapper) {
        this(new KafkaConsumer<>(consumerProperties(bootstrapServers)), objectMapper);
        consumer.subscribe(List.of(Objects.requireNonNull(topic, "topic must not be null")));
    }

    IntegrationProfileEvent await(UUID profileId, UUID tenantId, String eventType, Duration timeout) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            for (ConsumerRecord<String, String> record : records) {
                IntegrationProfileEvent event = decode(record);
                if (profileId.equals(event.profileId())
                        && tenantId.equals(event.tenantId())
                        && eventType.equals(event.eventType())) {
                    return event;
                }
            }
        }

        throw new AssertionError("Timed out after " + timeout + " waiting for integration profile event "
                + "[profileId=" + profileId + ", tenantId=" + tenantId + ", eventType=" + eventType + "]");
    }

    @Override
    public void close() {
        consumer.close();
    }

    private IntegrationProfileEvent decode(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readValue(record.value(), IntegrationProfileEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not decode integration profile event at "
                    + record.topic() + "-" + record.partition() + "@" + record.offset(), exception);
        }
    }

    private static Properties consumerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_PREFIX + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return properties;
    }
}
