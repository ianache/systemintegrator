package com.cl2.integration.integration.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxRelaySchedulerTest {
    private SpringDataOutboxRepository repository;
    private KafkaOutboxPublisher publisher;
    private OutboxRelayProperties properties;
    private com.cl2.integration.infrastructure.metrics.IntegrationMetrics metrics;
    private OutboxRelayScheduler scheduler;

    @BeforeEach
    void setup() {
        repository = mock(SpringDataOutboxRepository.class);
        publisher = mock(KafkaOutboxPublisher.class);
        properties = new OutboxRelayProperties();
        metrics = mock(com.cl2.integration.infrastructure.metrics.IntegrationMetrics.class);
        scheduler = new OutboxRelayScheduler(repository, publisher, properties, metrics);
    }

    @Test
    void shouldRelayPendingEventAndMarkPublished() {
        UUID tenantId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Vehicle", "vehicle.created", "{}");
        OutboxJpaEntity entity = OutboxJpaEntity.from(event);
        when(repository.findPendingForPublishing(any(), eq(50))).thenReturn(List.of(entity));
        when(publisher.publish(entity)).thenReturn(CompletableFuture.completedFuture(null));

        int processed = scheduler.relayBatch();

        assertThat(processed).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo("PUBLISHED");
        verify(repository).save(entity);
        verify(metrics).recordOutboxRelay(eq(tenantId.toString()), any(), eq(1), any(Double.class));
    }

    @Test
    void shouldHandlePublishingErrorWithBackoff() {
        OutboxEvent event = OutboxEvent.pending(UUID.randomUUID(), UUID.randomUUID(), "Vehicle", "vehicle.created", "{}");
        OutboxJpaEntity entity = OutboxJpaEntity.from(event);
        when(repository.findPendingForPublishing(any(), eq(50))).thenReturn(List.of(entity));
        when(publisher.publish(entity)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka Broker Down")));

        int processed = scheduler.relayBatch();

        assertThat(processed).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.toDomain().attempts()).isEqualTo(1);
        assertThat(entity.toDomain().lastError()).contains("Kafka Broker Down");
        verify(repository).save(entity);
    }

    @Test
    void shouldMarkTerminalFailedWhenMaxAttemptsReached() {
        properties.setMaxAttempts(3);
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Vehicle", "vehicle.created", "integration.events", "{}", "PENDING", 2, Instant.now(), null, null, Instant.now());
        OutboxJpaEntity entity = OutboxJpaEntity.from(event);
        when(repository.findPendingForPublishing(any(), eq(50))).thenReturn(List.of(entity));
        when(publisher.publish(entity)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Persistent failure")));

        int processed = scheduler.relayBatch();

        assertThat(processed).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.toDomain().attempts()).isEqualTo(3);
        verify(repository).save(entity);
    }

    @Test
    void shouldSkipRelayWhenDisabled() {
        properties.setEnabled(false);
        scheduler.pollAndRelay();
        verifyNoInteractions(repository);
        verifyNoInteractions(publisher);
    }
}
