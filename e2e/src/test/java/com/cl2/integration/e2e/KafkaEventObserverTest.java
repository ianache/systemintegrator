package com.cl2.integration.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cl2.integration.integration.profile.IntegrationProfileEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaEventObserverTest {

    private static final String TOPIC = "integration-profile.events";
    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID PROFILE_ID = UUID.fromString("7b4fe930-a3ce-43c1-9297-ff7a3c60f80c");

    @Test
    void returnsTheMatchingDecodedEventAfterSkippingUnrelatedRecords() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        UUID otherTenantId = UUID.fromString("b129386f-2ec1-4f2a-8d09-f2aed3b154c2");
        UUID otherProfileId = UUID.fromString("f65c1f03-6f97-40b3-9f66-52de1e64d073");
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            consumer.addRecord(record(0, otherProfileId, TENANT_ID, "IntegrationProfileCreated"));
            consumer.addRecord(record(1, PROFILE_ID, otherTenantId, "IntegrationProfileCreated"));
            consumer.addRecord(record(2, PROFILE_ID, TENANT_ID, "IntegrationProfileUpdated"));
            consumer.addRecord(record(3, PROFILE_ID, TENANT_ID, "IntegrationProfileCreated"));
        });

        try (KafkaEventObserver observer = new KafkaEventObserver(consumer, new ObjectMapper().findAndRegisterModules())) {
            IntegrationProfileEvent event = observer.await(
                    PROFILE_ID, TENANT_ID, "IntegrationProfileCreated", Duration.ofSeconds(1));

            assertThat(event.eventType()).isEqualTo("IntegrationProfileCreated");
            assertThat(event.profileId()).isEqualTo(PROFILE_ID);
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        }
    }

    private ConsumerRecord<String, String> record(long offset, UUID profileId, UUID tenantId, String eventType) {
        String payload = """
                {"eventId":"%s","eventType":"%s","profileId":"%s","tenantId":"%s","occurredAt":"2026-08-15T12:00:00Z","state":{"id":"%s","tenantId":"%s","businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM","active":true,"createdAt":"2026-08-15T12:00:00Z","updatedAt":"2026-08-15T12:00:00Z","version":0}}
                """.formatted(UUID.fromString("a4b7d7d2-7eca-4821-837c-c9f0679cd560"), eventType, profileId, tenantId, profileId, tenantId);
        return new ConsumerRecord<>(TOPIC, 0, offset, profileId.toString(), payload);
    }
}
