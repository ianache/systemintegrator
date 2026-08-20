package com.cl2.integration.integration;

import com.cl2.integration.IntegrationApplicationTest;
import com.cl2.integration.integration.inbox.DeadLetterQueuePublisher;
import com.cl2.integration.integration.inbox.InboxProcessor;
import com.cl2.integration.integration.inbox.InboxStore;
import com.cl2.integration.integration.outbox.KafkaOutboxPublisher;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxJpaEntity;
import com.cl2.integration.integration.outbox.OutboxRelayScheduler;
import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class OutboxInboxFlowIntegrationTest extends IntegrationApplicationTest {

    @Autowired
    private SpringDataOutboxRepository outboxRepository;

    @Autowired
    private OutboxRelayScheduler relayScheduler;

    @Autowired
    private InboxStore inboxStore;

    @Autowired
    private InboxProcessor inboxProcessor;

    @MockBean
    private KafkaOutboxPublisher kafkaOutboxPublisher;

    @MockBean
    private DeadLetterQueuePublisher deadLetterQueuePublisher;

    @Test
    void shouldRelayOutboxRecordAndProcessInInboxIdempotently() {
        UUID tenantId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(tenantId, aggregateId, "Vehicle", "vehicle.created", "{\"vin\":\"TEST-VIN-001\"}");

        outboxRepository.save(OutboxJpaEntity.from(event));

        when(kafkaOutboxPublisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

        int relayed = relayScheduler.relayBatch();
        assertThat(relayed).isGreaterThanOrEqualTo(1);

        AtomicInteger domainCalls = new AtomicInteger(0);
        boolean firstRun = inboxProcessor.process(event.id(), tenantId, event.eventType(), event.payload(), "integration.events", p -> domainCalls.incrementAndGet());
        boolean duplicateRun = inboxProcessor.process(event.id(), tenantId, event.eventType(), event.payload(), "integration.events", p -> domainCalls.incrementAndGet());

        assertThat(firstRun).isTrue();
        assertThat(duplicateRun).isFalse();
        assertThat(domainCalls.get()).isEqualTo(1);
    }
}
