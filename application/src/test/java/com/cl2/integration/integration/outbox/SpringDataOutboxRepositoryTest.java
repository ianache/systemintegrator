package com.cl2.integration.integration.outbox;

import com.cl2.integration.IntegrationApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpringDataOutboxRepositoryTest extends IntegrationApplicationTest {

    @Autowired
    private SpringDataOutboxRepository repository;

    @Autowired
    private OutboxRepository outboxRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private OutboxRelayScheduler outboxRelayScheduler;

    @Test
    @DisplayName("Should cancel pending outbox events for a given tenant and topic")
    void shouldCancelPendingEventsByTenantAndTopic() {
        UUID tenantId = UUID.randomUUID();
        String topic = "integration.units.events";

        OutboxEvent event1 = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"id\":1}");
        OutboxEvent event2 = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"id\":2}");
        OutboxEvent otherTenant = OutboxEvent.pending(UUID.randomUUID(), UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"id\":3}");
        OutboxEvent otherTopic = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Customer", "customers.created", "integration.customers.events", "{\"id\":4}");

        repository.save(OutboxJpaEntity.from(event1));
        repository.save(OutboxJpaEntity.from(event2));
        repository.save(OutboxJpaEntity.from(otherTenant));
        repository.save(OutboxJpaEntity.from(otherTopic));

        int cancelledCount = repository.cancelPendingByTenantAndTopic(tenantId, topic, "Profile deactivated");

        assertThat(cancelledCount).isEqualTo(2);

        OutboxJpaEntity entity1 = repository.findById(event1.id()).orElseThrow();
        assertThat(entity1.toDomain().status()).isEqualTo(OutboxStatus.CANCELLED.name());
        assertThat(entity1.toDomain().lastError()).isEqualTo("Profile deactivated");

        OutboxJpaEntity entity2 = repository.findById(event2.id()).orElseThrow();
        assertThat(entity2.toDomain().status()).isEqualTo(OutboxStatus.CANCELLED.name());

        OutboxJpaEntity otherTenantEntity = repository.findById(otherTenant.id()).orElseThrow();
        assertThat(otherTenantEntity.toDomain().status()).isEqualTo(OutboxStatus.PENDING.name());

        OutboxJpaEntity otherTopicEntity = repository.findById(otherTopic.id()).orElseThrow();
        assertThat(otherTopicEntity.toDomain().status()).isEqualTo(OutboxStatus.PENDING.name());
    }

    @Test
    void persistsExternalSourceAndStableDeliveryIdentities() {
        UUID tenantId = UUID.randomUUID();
        UUID firstDeliveryId = UUID.randomUUID();
        UUID secondDeliveryId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(
                tenantId,
                UUID.randomUUID(),
                "Customer",
                "customers.batch.upserted",
                "integration.customers.batch.events",
                "[{\"id\":1},{\"id\":2}]",
                "sap-hana");

        outboxRepository.save(event, List.of(firstDeliveryId, secondDeliveryId));

        OutboxEvent reloaded = repository.findById(event.id()).orElseThrow().toDomain();
        assertThat(reloaded.externalSource()).isEqualTo("sap-hana");
        assertThat(outboxRepository.findExistingDeliveryIds(
                tenantId,
                Set.of(firstDeliveryId, secondDeliveryId, UUID.randomUUID())))
                .containsExactlyInAnyOrder(firstDeliveryId, secondDeliveryId);
    }
}
