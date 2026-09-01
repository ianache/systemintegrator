package com.cl2.integration.integration.monitor;

import com.cl2.integration.integration.batch.BatchContext;
import com.cl2.integration.integration.inbox.InboxJpaEntity;
import com.cl2.integration.integration.inbox.SpringDataInboxRepository;
import com.cl2.integration.integration.outbound.OutboundEventDispatcher;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxJpaEntity;
import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageMonitorServiceTest {

    private final SpringDataInboxRepository inboxRepository = mock(SpringDataInboxRepository.class);
    private final SpringDataOutboxRepository outboxRepository = mock(SpringDataOutboxRepository.class);
    private final OutboundEventDispatcher dispatcher = mock(OutboundEventDispatcher.class);
    private final MessageMonitorService service = new MessageMonitorService(inboxRepository, outboxRepository, dispatcher);

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void listsInboundAndOutboundMessagesSortedByRecency() {
        UUID inboundId = UUID.randomUUID();
        UUID outboundId = UUID.randomUUID();
        InboxJpaEntity inbound = new InboxJpaEntity(inboundId, tenantId, "units.upserted", "{}", "PROCESSED", 0, Instant.parse("2026-08-20T10:00:00Z"));
        OutboxJpaEntity outbound = OutboxJpaEntity.from(new OutboxEvent(
                outboundId, tenantId, UUID.randomUUID(), "vehicle", "vehicle.created", "topic", "{}",
                "PENDING", 0, Instant.now(), null, null, Instant.parse("2026-08-21T10:00:00Z")));

        when(inboxRepository.findByTenantId(eq(tenantId), any())).thenReturn(List.of(inbound));
        when(outboxRepository.findByTenantId(eq(tenantId), any())).thenReturn(List.of(outbound));

        List<MessageSummary> result = service.list(tenantId, "ALL");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(outboundId); // more recent first
        assertThat(result.get(0).direction()).isEqualTo("OUTBOUND");
        assertThat(result.get(0).status()).isEqualTo("PENDING");
        assertThat(result.get(1).id()).isEqualTo(inboundId);
        assertThat(result.get(1).status()).isEqualTo("PROCESSED");
        assertThat(result.get(1).domain()).isEqualTo("units");
    }

    @Test
    void filtersByNormalizedStatus() {
        InboxJpaEntity deadLetter = new InboxJpaEntity(UUID.randomUUID(), tenantId, "units.upserted", "{}", "DEAD_LETTER", 1, Instant.now());
        InboxJpaEntity processed = new InboxJpaEntity(UUID.randomUUID(), tenantId, "units.upserted", "{}", "PROCESSED", 0, Instant.now());

        when(inboxRepository.findByTenantId(eq(tenantId), any())).thenReturn(List.of(deadLetter, processed));
        when(outboxRepository.findByTenantId(eq(tenantId), any())).thenReturn(List.of());

        List<MessageSummary> dlqOnly = service.list(tenantId, "DLQ");

        assertThat(dlqOnly).hasSize(1);
        assertThat(dlqOnly.get(0).status()).isEqualTo("DLQ");
    }

    @Test
    void filtersByDomain() {
        InboxJpaEntity units = new InboxJpaEntity(UUID.randomUUID(), tenantId, "units.upserted", "{}", "PROCESSED", 0, Instant.now());
        OutboxJpaEntity vehicles = OutboxJpaEntity.from(new OutboxEvent(
                UUID.randomUUID(), tenantId, UUID.randomUUID(), "vehicle", "vehicle.created", "topic", "{}",
                "PENDING", 0, Instant.now(), null, null, Instant.now()));

        when(inboxRepository.findByTenantId(eq(tenantId), any())).thenReturn(List.of(units));
        when(outboxRepository.findByTenantId(eq(tenantId), any())).thenReturn(List.of(vehicles));

        List<MessageSummary> result = service.list(tenantId, "ALL", "units", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).domain()).isEqualTo("units");
    }

    @Test
    void filtersByDateRange() {
        InboxJpaEntity older = new InboxJpaEntity(UUID.randomUUID(), tenantId, "units.upserted", "{}", "PROCESSED", 0, Instant.parse("2026-01-01T00:00:00Z"));
        InboxJpaEntity inRange = new InboxJpaEntity(UUID.randomUUID(), tenantId, "units.upserted", "{}", "PROCESSED", 0, Instant.parse("2026-08-20T00:00:00Z"));
        InboxJpaEntity newer = new InboxJpaEntity(UUID.randomUUID(), tenantId, "units.upserted", "{}", "PROCESSED", 0, Instant.parse("2026-12-01T00:00:00Z"));

        when(inboxRepository.findByTenantId(eq(tenantId), any())).thenReturn(List.of(older, inRange, newer));
        when(outboxRepository.findByTenantId(eq(tenantId), any())).thenReturn(List.of());

        List<MessageSummary> result = service.list(
                tenantId, "ALL", null,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).timestamp()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
    }

    @Test
    void retryingAnInboundMessageDispatchesAndMarksProcessedOnSuccess() {
        UUID eventId = UUID.randomUUID();
        InboxJpaEntity entity = new InboxJpaEntity(eventId, tenantId, "units.upserted", "{\"a\":1}", "DEAD_LETTER", 1, Instant.now());
        when(inboxRepository.findByEventIdAndTenantId(eventId, tenantId)).thenReturn(Optional.of(entity));

        MessageDetail result = service.retry(tenantId, "INBOUND", eventId);

        verify(dispatcher).dispatch(
                eventId,
                tenantId,
                "units.upserted",
                "{\"a\":1}",
                null,
                BatchContext.unitary());
        verify(inboxRepository).save(entity);
        assertThat(result.status()).isEqualTo("PROCESSED");
    }

    @Test
    void retryingABatchInboundMessageRecoversItsBatchContext() {
        UUID eventId = UUID.randomUUID();
        String payload = "[{\"id\":1},{\"id\":2}]";
        InboxJpaEntity entity = new InboxJpaEntity(
                eventId,
                tenantId,
                "units.batch.upserted",
                payload,
                "DEAD_LETTER",
                1,
                Instant.now());
        when(inboxRepository.findByEventIdAndTenantId(eventId, tenantId)).thenReturn(Optional.of(entity));

        service.retry(tenantId, "INBOUND", eventId);

        verify(dispatcher).dispatch(
                eventId,
                tenantId,
                "units.batch.upserted",
                payload,
                null,
                BatchContext.batch(2));
        assertThat(entity.getStatus()).isEqualTo("PROCESSED");
    }

    @Test
    void retryingAnInboundMessageMarksDeadLetterAgainOnFailure() {
        UUID eventId = UUID.randomUUID();
        InboxJpaEntity entity = new InboxJpaEntity(eventId, tenantId, "units.upserted", "{}", "DEAD_LETTER", 1, Instant.now());
        when(inboxRepository.findByEventIdAndTenantId(eventId, tenantId)).thenReturn(Optional.of(entity));
        doThrow(new RuntimeException("still down")).when(dispatcher).dispatch(
                eq(eventId),
                eq(tenantId),
                any(),
                any(),
                isNull(),
                eq(BatchContext.unitary()));

        MessageDetail result = service.retry(tenantId, "INBOUND", eventId);

        assertThat(result.status()).isEqualTo("DLQ");
        assertThat(result.lastError()).isEqualTo("still down");
    }

    @Test
    void retryingAnOutboundMessageResetsItToPendingForTheScheduler() {
        UUID id = UUID.randomUUID();
        OutboxJpaEntity entity = OutboxJpaEntity.from(new OutboxEvent(
                id, tenantId, UUID.randomUUID(), "vehicle", "vehicle.created", "topic", "{}",
                "FAILED", 5, Instant.now(), null, "boom", Instant.now()));
        when(outboxRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(entity));

        MessageDetail result = service.retry(tenantId, "OUTBOUND", id);

        assertThat(result.status()).isEqualTo("PENDING");
        verify(outboxRepository).save(entity);
    }

    @Test
    void movingAnOutboundMessageToDlqMarksItFailed() {
        UUID id = UUID.randomUUID();
        OutboxJpaEntity entity = OutboxJpaEntity.from(new OutboxEvent(
                id, tenantId, UUID.randomUUID(), "vehicle", "vehicle.created", "topic", "{}",
                "PENDING", 0, Instant.now(), null, null, Instant.now()));
        when(outboxRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(entity));

        MessageDetail result = service.moveToDlq(tenantId, "OUTBOUND", id);

        assertThat(result.status()).isEqualTo("DLQ");
    }

    @Test
    void findingAMissingMessageThrows() {
        UUID id = UUID.randomUUID();
        when(inboxRepository.findByEventIdAndTenantId(id, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(tenantId, "INBOUND", id))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void rejectsAnUnsupportedDirection() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.find(tenantId, "SIDEWAYS", id))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
