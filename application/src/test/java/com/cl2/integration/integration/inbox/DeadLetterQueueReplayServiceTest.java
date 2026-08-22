package com.cl2.integration.integration.inbox;

import com.cl2.integration.integration.outbound.OutboundEventDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeadLetterQueueReplayServiceTest {

    @Test
    @DisplayName("Should replay dead letter messages and mark as PROCESSED on success")
    void shouldReplayDeadLetterMessagesSuccessfully() {
        SpringDataInboxRepository repository = mock(SpringDataInboxRepository.class);
        OutboundEventDispatcher dispatcher = mock(OutboundEventDispatcher.class);
        DeadLetterQueueReplayService service = new DeadLetterQueueReplayService(repository, dispatcher);

        UUID tenantId = UUID.randomUUID();
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();

        InboxJpaEntity entity1 = new InboxJpaEntity(eventId1, tenantId, "units.upserted", "{\"id\":1}", "DEAD_LETTER", 10, Instant.now());
        InboxJpaEntity entity2 = new InboxJpaEntity(eventId2, tenantId, "units.upserted", "{\"id\":2}", "DEAD_LETTER", 10, Instant.now());

        when(repository.findByTenantIdAndStatus(tenantId, "DEAD_LETTER")).thenReturn(List.of(entity1, entity2));

        // When entity2 fails during dispatch
        doThrow(new RuntimeException("Remote endpoint down")).when(dispatcher).dispatch(eq(eventId2), eq(tenantId), eq("units.upserted"), eq("{\"id\":2}"), any());

        DeadLetterQueueReplayService.ReplaySummary summary = service.replay(tenantId);

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.success()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);

        assertThat(entity1.getStatus()).isEqualTo("PROCESSED");
        assertThat(entity2.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(entity2.getLastError()).isEqualTo("Remote endpoint down");

        verify(repository, times(2)).save(any(InboxJpaEntity.class));
    }
}
