package com.cl2.integration.integration.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class OutboxRelayScheduler {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final SpringDataOutboxRepository repository;
    private final KafkaOutboxPublisher publisher;
    private final OutboxRelayProperties properties;

    public OutboxRelayScheduler(SpringDataOutboxRepository repository, KafkaOutboxPublisher publisher, OutboxRelayProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${integration.outbox.relay.fixed-delay-ms:1000}")
    public void pollAndRelay() {
        if (!properties.isEnabled()) {
            return;
        }
        relayBatch();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int relayBatch() {
        Instant now = Instant.now();
        List<OutboxJpaEntity> pending = repository.findPendingForPublishing(now, properties.getBatchSize());
        for (OutboxJpaEntity entity : pending) {
            try {
                publisher.publish(entity).get(); // synchronous delivery confirmation in relay worker
                entity.markPublished(Instant.now());
                repository.save(entity);
                log.debug("Successfully relayed outbox event id={}", entity.getId());
            } catch (Exception ex) {
                log.warn("Failed to publish outbox event id={}: {}", entity.getId(), ex.getMessage());
                boolean terminal = (entity.toDomain().attempts() + 1) >= properties.getMaxAttempts();
                long backoffMs = properties.getInitialBackoffMs() * (1L << Math.min(entity.toDomain().attempts(), 10));
                Instant nextAvailableAt = Instant.now().plus(Duration.ofMillis(backoffMs));
                entity.markFailed(ex.getMessage(), nextAvailableAt, terminal);
                repository.save(entity);
            }
        }
        return pending.size();
    }
}
