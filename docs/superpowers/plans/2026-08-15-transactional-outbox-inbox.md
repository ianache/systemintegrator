# Transactional Outbox & Inbox Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a resilient, multitenant Transactional Outbox (Relay with `SKIP LOCKED` and exponential backoff) and Inbox Pattern (idempotency, deduplication, retry, and Dead Letter Queue) in Spring Boot 3 / Java 21 / MySQL 8 / Apache Kafka.

**Architecture:** Transactional Outbox ensures business mutations and domain events are committed atomically in MySQL. A non-blocking scheduled Relay queries pending events using `FOR UPDATE SKIP LOCKED` and publishes them to Kafka with headers. An Inbox consumer guarantees idempotency, records incoming payloads, retries transient failures, and routes poisoned messages to a DLQ topic.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring Data JPA / Hibernate, Flyway, Apache Kafka (Spring Kafka), MySQL 8, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Java 21 with records, pattern matching, and standard style.
- Multitenant-aware: all operations must carry and propagate `tenant_id` (UUID).
- Non-blocking concurrency: Outbox relay batch query must use MySQL `FOR UPDATE SKIP LOCKED`.
- Resilient error handling: Exponential backoff on retries and DLQ routing on exhausted attempts.
- All tests must pass with `mvn test`.

---

### Task 1: Database Migration & Entity Upgrades (Outbox & Inbox)

**Files:**
- Create: `src/main/resources/db/migration/V4__enhance_outbox_inbox_dlq.sql`
- Modify: `src/main/java/com/cl2/integration/integration/outbox/OutboxEvent.java`
- Modify: `src/main/java/com/cl2/integration/integration/outbox/OutboxJpaEntity.java`
- Modify: `src/main/java/com/cl2/integration/integration/inbox/InboxJpaEntity.java`
- Modify: `src/main/java/com/cl2/integration/integration/inbox/SpringDataInboxRepository.java`
- Test: `src/test/java/com/cl2/integration/integration/outbox/OutboxEntityTest.java`
- Test: `src/test/java/com/cl2/integration/integration/inbox/InboxEntityTest.java`

**Interfaces:**
- Consumes: MySQL schema `V2__create_vehicle_outbox_inbox.sql`
- Produces: `OutboxEvent`, `OutboxJpaEntity`, `InboxJpaEntity`, `SpringDataInboxRepository` supporting `status`, `attempts`, `available_at`, `topic`, and `payload`.

- [ ] **Step 1: Write Flyway migration `V4__enhance_outbox_inbox_dlq.sql`**

```sql
ALTER TABLE integration_outbox
    ADD COLUMN IF NOT EXISTS topic VARCHAR(150) NULL AFTER event_type;

CREATE INDEX idx_outbox_relay ON integration_outbox (status, available_at, created_at);

ALTER TABLE integration_inbox
    ADD COLUMN IF NOT EXISTS payload JSON NULL AFTER event_type;

CREATE INDEX idx_inbox_retry ON integration_inbox (tenant_id, status, attempts);
```

- [ ] **Step 2: Write failing unit test for Outbox & Inbox Entity mapping**

Create `src/test/java/com/cl2/integration/integration/outbox/OutboxEntityTest.java`:
```java
package com.cl2.integration.integration.outbox;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxEntityTest {
    @Test
    void shouldCreateAndConvertOutboxEventWithTopic() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(id, tenantId, aggregateId, "Vehicle", "vehicle.created", "integration.events", "{\"vin\":\"123\"}", "PENDING", 0, Instant.now(), null, null, Instant.now());
        
        OutboxJpaEntity entity = OutboxJpaEntity.from(event);
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getTopic()).isEqualTo("integration.events");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=OutboxEntityTest`
Expected: Compilation failure or test failure (missing constructor / getters).

- [ ] **Step 4: Update `OutboxEvent.java` and `OutboxJpaEntity.java`**

Update `src/main/java/com/cl2/integration/integration/outbox/OutboxEvent.java`:
```java
package com.cl2.integration.integration.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
    UUID id,
    UUID tenantId,
    UUID aggregateId,
    String aggregateType,
    String eventType,
    String topic,
    String payload,
    String status,
    int attempts,
    Instant availableAt,
    Instant publishedAt,
    String lastError,
    Instant createdAt
) {
    public static OutboxEvent pending(UUID tenantId, UUID aggregateId, String aggregateType, String eventType, String topic, String payload) {
        Instant now = Instant.now();
        return new OutboxEvent(UUID.randomUUID(), tenantId, aggregateId, aggregateType, eventType, topic, payload, "PENDING", 0, now, null, null, now);
    }

    public static OutboxEvent pending(UUID tenantId, UUID aggregateId, String aggregateType, String eventType, String payload) {
        return pending(tenantId, aggregateId, aggregateType, eventType, "integration.events", payload);
    }
}
```

Update `src/main/java/com/cl2/integration/integration/outbox/OutboxJpaEntity.java`:
```java
package com.cl2.integration.integration.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "integration_outbox")
public class OutboxJpaEntity {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(name = "topic", length = 150)
    private String topic;

    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxJpaEntity() {}

    private OutboxJpaEntity(OutboxEvent event) {
        this.id = event.id();
        this.tenantId = event.tenantId();
        this.aggregateType = event.aggregateType();
        this.aggregateId = event.aggregateId();
        this.eventType = event.eventType();
        this.topic = event.topic();
        this.payload = event.payload();
        this.status = event.status();
        this.attempts = event.attempts();
        this.availableAt = event.availableAt();
        this.publishedAt = event.publishedAt();
        this.lastError = event.lastError();
        this.createdAt = event.createdAt();
    }

    public static OutboxJpaEntity from(OutboxEvent event) {
        return new OutboxJpaEntity(event);
    }

    public OutboxEvent toDomain() {
        return new OutboxEvent(id, tenantId, aggregateId, aggregateType, eventType, topic, payload, status, attempts, availableAt, publishedAt, lastError, createdAt);
    }

    public UUID getId() { return id; }
    public String getStatus() { return status; }
    public String getTopic() { return topic; }
    public void markPublished(Instant publishedAt) {
        this.status = "PUBLISHED";
        this.publishedAt = publishedAt;
        this.lastError = null;
    }
    public void markFailed(String error, Instant nextAvailableAt, boolean terminal) {
        this.attempts++;
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 997) + "..." : error;
        this.availableAt = nextAvailableAt;
        if (terminal) {
            this.status = "FAILED";
        }
    }
}
```

- [ ] **Step 5: Update `InboxJpaEntity.java` and `SpringDataInboxRepository.java`**

Update `src/main/java/com/cl2/integration/integration/inbox/InboxJpaEntity.java`:
```java
package com.cl2.integration.integration.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "integration_inbox")
public class InboxJpaEntity {
    @Id
    @Column(name = "event_id", columnDefinition = "BINARY(16)")
    private UUID eventId;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(columnDefinition = "JSON")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected InboxJpaEntity() {}

    public InboxJpaEntity(UUID eventId, UUID tenantId, String eventType, String payload, String status, int attempts, Instant receivedAt) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.receivedAt = receivedAt;
    }

    public UUID getEventId() { return eventId; }
    public UUID getTenantId() { return tenantId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }

    public void markProcessed(Instant processedAt) {
        this.status = "PROCESSED";
        this.processedAt = processedAt;
        this.lastError = null;
    }

    public void markDeadLetter(String error) {
        this.attempts++;
        this.status = "DEAD_LETTER";
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 997) + "..." : error;
    }

    public void recordAttempt(String error) {
        this.attempts++;
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 997) + "..." : error;
    }
}
```

Update `src/main/java/com/cl2/integration/integration/inbox/SpringDataInboxRepository.java`:
```java
package com.cl2.integration.integration.inbox;

import org.springframework.data.repository.Repository;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataInboxRepository extends Repository<InboxJpaEntity, UUID> {
    InboxJpaEntity save(InboxJpaEntity entity);
    Optional<InboxJpaEntity> findById(UUID eventId);
    Optional<InboxJpaEntity> findByEventIdAndTenantId(UUID eventId, UUID tenantId);
    boolean existsByEventIdAndTenantId(UUID eventId, UUID tenantId);
}
```

- [ ] **Step 6: Run tests and commit**

Run: `mvn test`
Expected: PASS
```bash
git add src/main/resources/db/migration/V4__enhance_outbox_inbox_dlq.sql src/main/java/com/cl2/integration/integration/outbox/ src/main/java/com/cl2/integration/integration/inbox/ src/test/java/com/cl2/integration/integration/
git commit -m "feat(core): enhance outbox and inbox entities and migration for relay and dlq"
```

---

### Task 2: Outbox Relay Engine with Concurrency & Exponential Backoff

**Files:**
- Modify: `src/main/java/com/cl2/integration/integration/outbox/SpringDataOutboxRepository.java`
- Modify: `src/main/java/com/cl2/integration/integration/outbox/KafkaOutboxPublisher.java`
- Create: `src/main/java/com/cl2/integration/integration/outbox/OutboxRelayScheduler.java`
- Create: `src/main/java/com/cl2/integration/integration/outbox/OutboxRelayProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/cl2/integration/integration/outbox/OutboxRelaySchedulerTest.java`

**Interfaces:**
- Consumes: `OutboxJpaEntity`, `SpringDataOutboxRepository`, `KafkaTemplate<String, String>`
- Produces: `OutboxRelayScheduler` querying pending events with `FOR UPDATE SKIP LOCKED` and publishing with headers `X-Tenant-ID`, `X-Event-Type`.

- [ ] **Step 1: Update `SpringDataOutboxRepository` with native batch query**

```java
package com.cl2.integration.integration.outbox;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataOutboxRepository extends Repository<OutboxJpaEntity, UUID> {
    OutboxJpaEntity save(OutboxJpaEntity entity);
    Optional<OutboxJpaEntity> findById(UUID id);

    @Query(value = "SELECT * FROM integration_outbox WHERE status = 'PENDING' AND available_at <= :now ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxJpaEntity> findPendingForPublishing(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
```

- [ ] **Step 2: Update `KafkaOutboxPublisher` to handle topics, headers and publisher callbacks**

```java
package com.cl2.integration.integration.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaOutboxPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<?> publish(OutboxJpaEntity event) {
        String topic = event.getTopic() != null && !event.getTopic().isBlank() ? event.getTopic() : "integration.events";
        ProducerRecord<String, String> record = new ProducerRecord<>(
            topic,
            event.getId().toString(),
            event.toDomain().payload()
        );
        record.headers().add(new RecordHeader("X-Tenant-ID", event.toDomain().tenantId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Event-Type", event.toDomain().eventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Aggregate-ID", event.toDomain().aggregateId().toString().getBytes(StandardCharsets.UTF_8)));

        return kafkaTemplate.send(record);
    }
}
```

- [ ] **Step 3: Create `OutboxRelayProperties` and `OutboxRelayScheduler`**

Create `src/main/java/com/cl2/integration/integration/outbox/OutboxRelayProperties.java`:
```java
package com.cl2.integration.integration.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "integration.outbox.relay")
public class OutboxRelayProperties {
    private boolean enabled = true;
    private int batchSize = 50;
    private int maxAttempts = 5;
    private long initialBackoffMs = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getInitialBackoffMs() { return initialBackoffMs; }
    public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
}
```

Create `src/main/java/com/cl2/integration/integration/outbox/OutboxRelayScheduler.java`:
```java
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
```

- [ ] **Step 4: Write unit test `OutboxRelaySchedulerTest.java`**

Create `src/test/java/com/cl2/integration/integration/outbox/OutboxRelaySchedulerTest.java`:
```java
package com.cl2.integration.integration.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
    private OutboxRelayScheduler scheduler;

    @BeforeEach
    void setup() {
        repository = mock(SpringDataOutboxRepository.class);
        publisher = mock(KafkaOutboxPublisher.class);
        properties = new OutboxRelayProperties();
        scheduler = new OutboxRelayScheduler(repository, publisher, properties);
    }

    @Test
    void shouldRelayPendingEventAndMarkPublished() {
        OutboxEvent event = OutboxEvent.pending(UUID.randomUUID(), UUID.randomUUID(), "Vehicle", "vehicle.created", "{}");
        OutboxJpaEntity entity = OutboxJpaEntity.from(event);
        when(repository.findPendingForPublishing(any(), eq(50))).thenReturn(List.of(entity));
        when(publisher.publish(entity)).thenReturn(CompletableFuture.completedFuture(null));

        int processed = scheduler.relayBatch();

        assertThat(processed).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo("PUBLISHED");
        verify(repository).save(entity);
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
}
```

- [ ] **Step 5: Run tests and commit**

Run: `mvn test -Dtest=OutboxRelaySchedulerTest`
Expected: PASS
```bash
git add src/main/java/com/cl2/integration/integration/outbox/ src/test/java/com/cl2/integration/integration/outbox/
git commit -m "feat(outbox): implement non-blocking outbox relay with backoff and retry"
```

---

### Task 3: Inbox Consumer, Idempotence, Retry & DLQ Dispatcher

**Files:**
- Modify: `src/main/java/com/cl2/integration/integration/inbox/InboxStore.java`
- Modify: `src/main/java/com/cl2/integration/integration/inbox/InboxPersistenceAdapter.java`
- Modify: `src/main/java/com/cl2/integration/integration/inbox/InboxProcessor.java`
- Create: `src/main/java/com/cl2/integration/integration/inbox/DeadLetterQueuePublisher.java`
- Create: `src/main/java/com/cl2/integration/integration/inbox/KafkaInboxListener.java`
- Test: `src/test/java/com/cl2/integration/integration/inbox/InboxProcessorTest.java`

**Interfaces:**
- Consumes: `SpringDataInboxRepository`, `KafkaTemplate<String, String>`
- Produces: `InboxProcessor`, `KafkaInboxListener`, `DeadLetterQueuePublisher` with idempotency deduplication and DLQ routing.

- [ ] **Step 1: Update `InboxStore` and `InboxPersistenceAdapter`**

Update `src/main/java/com/cl2/integration/integration/inbox/InboxStore.java`:
```java
package com.cl2.integration.integration.inbox;

import java.util.Optional;
import java.util.UUID;

public interface InboxStore {
    boolean recordIfAbsent(UUID eventId, UUID tenantId, String eventType, String payload);
    Optional<InboxJpaEntity> find(UUID eventId, UUID tenantId);
    void markProcessed(UUID eventId, UUID tenantId);
    void markDeadLetter(UUID eventId, UUID tenantId, String error);
}
```

Update `src/main/java/com/cl2/integration/integration/inbox/InboxPersistenceAdapter.java`:
```java
package com.cl2.integration.integration.inbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class InboxPersistenceAdapter implements InboxStore {
    private final SpringDataInboxRepository repository;

    public InboxPersistenceAdapter(SpringDataInboxRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public boolean recordIfAbsent(UUID eventId, UUID tenantId, String eventType, String payload) {
        Optional<InboxJpaEntity> existing = repository.findByEventIdAndTenantId(eventId, tenantId);
        if (existing.isPresent()) {
            return false;
        }
        repository.save(new InboxJpaEntity(eventId, tenantId, eventType, payload, "RECEIVED", 0, Instant.now()));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboxJpaEntity> find(UUID eventId, UUID tenantId) {
        return repository.findByEventIdAndTenantId(eventId, tenantId);
    }

    @Override
    @Transactional
    public void markProcessed(UUID eventId, UUID tenantId) {
        repository.findByEventIdAndTenantId(eventId, tenantId).ifPresent(entity -> {
            entity.markProcessed(Instant.now());
            repository.save(entity);
        });
    }

    @Override
    @Transactional
    public void markDeadLetter(UUID eventId, UUID tenantId, String error) {
        repository.findByEventIdAndTenantId(eventId, tenantId).ifPresent(entity -> {
            entity.markDeadLetter(error);
            repository.save(entity);
        });
    }
}
```

- [ ] **Step 2: Create `DeadLetterQueuePublisher` and `KafkaInboxListener`**

Create `src/main/java/com/cl2/integration/integration/inbox/DeadLetterQueuePublisher.java`:
```java
package com.cl2.integration.integration.inbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class DeadLetterQueuePublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;

    public DeadLetterQueuePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishToDlq(String originalTopic, UUID eventId, UUID tenantId, String payload, String errorMessage) {
        String dlqTopic = (originalTopic != null ? originalTopic : "integration.events") + ".dlq";
        ProducerRecord<String, String> record = new ProducerRecord<>(dlqTopic, eventId.toString(), payload);
        if (tenantId != null) {
            record.headers().add(new RecordHeader("X-Tenant-ID", tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        }
        if (errorMessage != null) {
            record.headers().add(new RecordHeader("X-Error-Message", errorMessage.getBytes(StandardCharsets.UTF_8)));
        }
        kafkaTemplate.send(record);
    }
}
```

Update `src/main/java/com/cl2/integration/integration/inbox/InboxProcessor.java`:
```java
package com.cl2.integration.integration.inbox;

import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class InboxProcessor {
    private final InboxStore store;
    private final DeadLetterQueuePublisher dlqPublisher;

    public InboxProcessor(InboxStore store, DeadLetterQueuePublisher dlqPublisher) {
        this.store = store;
        this.dlqPublisher = dlqPublisher;
    }

    public boolean process(UUID eventId, UUID tenantId, String eventType, String payload, String topic, Consumer<String> domainHandler) {
        boolean isNew = store.recordIfAbsent(eventId, tenantId, eventType, payload);
        if (!isNew) {
            var existing = store.find(eventId, tenantId);
            if (existing.isPresent() && "PROCESSED".equals(existing.get().getStatus())) {
                return false; // already processed, idempotently ignore
            }
        }

        try {
            domainHandler.accept(payload);
            store.markProcessed(eventId, tenantId);
            return true;
        } catch (Exception ex) {
            store.markDeadLetter(eventId, tenantId, ex.getMessage());
            dlqPublisher.publishToDlq(topic, eventId, tenantId, payload, ex.getMessage());
            throw new RuntimeException("Inbox processing failed, forwarded to DLQ", ex);
        }
    }
}
```

- [ ] **Step 3: Write Unit Test `InboxProcessorTest.java`**

Create `src/test/java/com/cl2/integration/integration/inbox/InboxProcessorTest.java`:
```java
package com.cl2.integration.integration.inbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InboxProcessorTest {
    private InboxStore store;
    private DeadLetterQueuePublisher dlq;
    private InboxProcessor processor;

    @BeforeEach
    void setup() {
        store = mock(InboxStore.class);
        dlq = mock(DeadLetterQueuePublisher.class);
        processor = new InboxProcessor(store, dlq);
    }

    @Test
    void shouldProcessNewEventSuccessfully() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(store.recordIfAbsent(eventId, tenantId, "VehicleCreated", "{}")).thenReturn(true);

        boolean processed = processor.process(eventId, tenantId, "VehicleCreated", "{}", "integration.events", payload -> {});

        assertThat(processed).isTrue();
        verify(store).markProcessed(eventId, tenantId);
        verifyNoInteractions(dlq);
    }

    @Test
    void shouldSkipDuplicateAlreadyProcessedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(store.recordIfAbsent(eventId, tenantId, "VehicleCreated", "{}")).thenReturn(false);
        InboxJpaEntity existing = new InboxJpaEntity(eventId, tenantId, "VehicleCreated", "{}", "PROCESSED", 1, Instant.now());
        when(store.find(eventId, tenantId)).thenReturn(Optional.of(existing));

        boolean processed = processor.process(eventId, tenantId, "VehicleCreated", "{}", "integration.events", payload -> {});

        assertThat(processed).isFalse();
        verify(store, never()).markProcessed(any(), any());
        verifyNoInteractions(dlq);
    }

    @Test
    void shouldForwardToDlqOnDomainFailure() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(store.recordIfAbsent(eventId, tenantId, "VehicleCreated", "{}")).thenReturn(true);

        assertThatThrownBy(() -> processor.process(eventId, tenantId, "VehicleCreated", "{}", "integration.events", payload -> {
            throw new IllegalArgumentException("Invalid payload");
        })).isInstanceOf(RuntimeException.class);

        verify(store).markDeadLetter(eq(eventId), eq(tenantId), contains("Invalid payload"));
        verify(dlq).publishToDlq(eq("integration.events"), eq(eventId), eq(tenantId), eq("{}"), contains("Invalid payload"));
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `mvn test -Dtest=InboxProcessorTest`
Expected: PASS
```bash
git add src/main/java/com/cl2/integration/integration/inbox/ src/test/java/com/cl2/integration/integration/inbox/
git commit -m "feat(inbox): implement idempotent inbox processor with DLQ forwarding"
```

---

### Task 4: End-to-End Test & Verification Suite

**Files:**
- Create: `src/test/java/com/cl2/integration/integration/OutboxInboxFlowIntegrationTest.java`
- Modify: `docs/test-cases/test-cases-outbox-inbox.md`

**Interfaces:**
- Validates the full transactional pipeline: Domain Mutation ➔ Atomic Outbox INSERT ➔ Relay Poll ➔ Kafka Dispatch ➔ Inbox Deduplication / DLQ.

- [ ] **Step 1: Create Integration Test `OutboxInboxFlowIntegrationTest.java`**

Create `src/test/java/com/cl2/integration/integration/OutboxInboxFlowIntegrationTest.java`:
```java
package com.cl2.integration.integration;

import com.cl2.integration.integration.inbox.DeadLetterQueuePublisher;
import com.cl2.integration.integration.inbox.InboxProcessor;
import com.cl2.integration.integration.inbox.InboxStore;
import com.cl2.integration.integration.outbox.KafkaOutboxPublisher;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxRelayProperties;
import com.cl2.integration.integration.outbox.OutboxRelayScheduler;
import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class OutboxInboxFlowIntegrationTest {

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
        
        outboxRepository.save(com.cl2.integration.integration.outbox.OutboxJpaEntity.from(event));

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
```

- [ ] **Step 2: Run all project tests**

Run: `mvn clean test`
Expected: All tests PASS with zero failures.

- [ ] **Step 3: Document manual and automated test execution in `docs/test-cases/test-cases-outbox-inbox.md`**

- [ ] **Step 4: Commit and finalize**

```bash
git add src/test/java/com/cl2/integration/integration/ docs/test-cases/
git commit -m "test(integration): add full outbox to inbox integration flow test suite"
```
