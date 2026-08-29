package com.cl2.integration.integration.inbox;

import com.cl2.integration.integration.batch.BatchContext;
import com.cl2.integration.integration.batch.BatchContextResolver;
import com.cl2.integration.integration.outbound.OutboundEventDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeadLetterQueueReplayService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueReplayService.class);

    private final SpringDataInboxRepository repository;
    private final OutboundEventDispatcher outboundEventDispatcher;
    private final BatchContextResolver batchContextResolver;

    public DeadLetterQueueReplayService(
            SpringDataInboxRepository repository,
            OutboundEventDispatcher outboundEventDispatcher) {
        this(repository, outboundEventDispatcher, new BatchContextResolver(new ObjectMapper()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeadLetterQueueReplayService(
            SpringDataInboxRepository repository,
            OutboundEventDispatcher outboundEventDispatcher,
            BatchContextResolver batchContextResolver) {
        this.repository = repository;
        this.outboundEventDispatcher = outboundEventDispatcher;
        this.batchContextResolver = batchContextResolver;
    }

    @Transactional
    public ReplaySummary replay(UUID tenantId) {
        List<InboxJpaEntity> deadLetters = (tenantId != null)
                ? repository.findByTenantIdAndStatus(tenantId, "DEAD_LETTER")
                : repository.findByStatus("DEAD_LETTER");

        log.info("Starting DLQ replay for {} messages (tenantId={})", deadLetters.size(), tenantId);

        int successCount = 0;
        int failureCount = 0;

        for (InboxJpaEntity entity : deadLetters) {
            try {
                // Dispatch event through outbound dispatcher
                BatchContext batchContext = batchContextResolver.recoverFromEvent(
                        entity.getEventType(), entity.getPayload());
                outboundEventDispatcher.dispatch(
                        entity.getEventId(),
                        entity.getTenantId(),
                        entity.getEventType(),
                        entity.getPayload(),
                        null,
                        batchContext);
                entity.markProcessed(Instant.now());
                repository.save(entity);
                successCount++;
            } catch (Exception ex) {
                log.warn("Replay failed for eventId={}: {}", entity.getEventId(), ex.getMessage());
                entity.markDeadLetter(ex.getMessage());
                repository.save(entity);
                failureCount++;
            }
        }

        log.info("DLQ replay finished: total={}, success={}, failed={}", deadLetters.size(), successCount, failureCount);
        return new ReplaySummary(deadLetters.size(), successCount, failureCount);
    }

    public record ReplaySummary(int total, int success, int failed) {}
}
