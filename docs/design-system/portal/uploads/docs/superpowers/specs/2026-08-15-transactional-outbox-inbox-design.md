# Design Spec: Transactional Outbox & Inbox Core Patterns

- **Date:** 2026-08-15
- **Status:** Approved
- **Scope:** Core Resilience & Event-Driven Integration (Outbox Relay & Inbox Idempotency/DLQ)

---

## 1. Context & Objective

As established in the Multitenant Integration Platform PRD (v2.0), domain microservices must remain completely decoupled from external systems and protocols. To guarantee transactional consistency and fault tolerance:
1. **Transactional Outbox**: Business mutations and outbound event records are committed in the same atomic MySQL transaction. An autonomous, non-blocking Relay publishes events to Apache Kafka.
2. **Inbox Pattern**: Inbound events from Kafka (whether produced by internal services, webhooks, or CDC pipelines) are deduplicated, audited, processed idempotently, retried with exponential backoff, and routed to a Dead Letter Queue (DLQ) upon unrecoverable failure.

---

## 2. Architecture & Data Model

### 2.1 Database Schema Enhancements (`V4__enhance_outbox_inbox_dlq.sql`)

```sql
-- Enhancements for integration_outbox
ALTER TABLE integration_outbox
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS topic VARCHAR(150) NULL AFTER event_type;

-- Ensure proper indexing for poller performance
CREATE INDEX idx_outbox_relay ON integration_outbox (status, available_at, created_at);

-- Enhancements for integration_inbox
ALTER TABLE integration_inbox
    ADD COLUMN IF NOT EXISTS payload JSON NULL AFTER event_type,
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED';

CREATE INDEX idx_inbox_retry ON integration_inbox (status, attempts);
```

### 2.2 Lifecycle States

#### Outbox Lifecycle
* `PENDING`: Initial state upon commit. Ready for relay.
* `PUBLISHED`: Successfully delivered to Kafka broker with producer ack.
* `FAILED`: Delivery failed after reaching `max-attempts` threshold.

#### Inbox Lifecycle
* `RECEIVED`: Message captured from Kafka; deduplication verified by `(event_id, tenant_id)`.
* `PROCESSING`: Business domain dispatcher actively processing the payload.
* `PROCESSED`: Domain transaction committed successfully.
* `DEAD_LETTER`: Retries exhausted or unrecoverable error; payload forwarded to DLQ topic.

---

## 3. Component Architecture & Data Flow

### 3.1 Outbox Relay Engine
1. **`OutboxRelayScheduler`**:
   * Runs as a scheduled task (`@Scheduled(fixedDelayString = "${integration.outbox.relay.fixed-delay-ms:1000}")`).
   * Fetches batch with `SELECT * FROM integration_outbox WHERE status = 'PENDING' AND available_at <= NOW(6) ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED`.
2. **`KafkaOutboxPublisher`**:
   * Dispatches messages to Kafka with headers (`X-Tenant-ID`, `X-Event-Type`, `X-Aggregate-Id`).
   * On success: Updates status to `PUBLISHED` and sets `published_at = NOW(6)`.
   * On error: Increments `attempts`, calculates `available_at = NOW(6) + (base_backoff * 2^attempts)`, and updates status to `FAILED` if `attempts >= max_attempts`.

### 3.2 Inbox Consumer & DLQ Engine
1. **`KafkaInboxListener`**:
   * Listens to incoming domain topics (e.g. `integration.events`, `integration.vehicle.events`).
   * Extracts `eventId`, `tenantId`, and metadata headers.
2. **`InboxManager`**:
   * Checks idempotency: if `eventId` already exists in `PROCESSED` status for the tenant, message is acknowledged and skipped.
   * If new, persists record in `RECEIVED` status.
3. **`DomainEventDispatcher`**:
   * Dispatches payload to the corresponding domain handler.
   * Updates state to `PROCESSED` on success.
4. **`DeadLetterQueuePublisher`**:
   * If handler throws an unrecoverable exception or retries exceed limit, forwards the envelope to `integration.<domain>.dlq` and transitions inbox record to `DEAD_LETTER`.

---

## 4. Configuration Properties (`application.yml`)

```yaml
integration:
  outbox:
    relay:
      enabled: true
      fixed-delay-ms: 1000
      batch-size: 50
      max-attempts: 5
      initial-backoff-ms: 1000
  inbox:
    max-attempts: 3
    dlq:
      topic-suffix: .dlq
```

---

## 5. Testing & Verification Strategy

1. **Transactional Atomicity Tests**:
   * Rollback in domain operation must roll back outbox record.
   * Successful commit must create `PENDING` outbox record.
2. **Concurrent Relay Execution (`SKIP LOCKED`)**:
   * Parallel execution of relay tasks across multiple threads/instances without duplicate Kafka publications or deadlocks.
3. **Inbox Idempotency & Deduplication**:
   * Duplicate message delivery does not trigger duplicate domain processing.
4. **Retry & DLQ Routing**:
   * Transient errors trigger retries with backoff.
   * Exhausted retries result in message published to DLQ and status `DEAD_LETTER`.
5. **Full Integration Test Suite**:
   * `mvn clean test` must pass all unit and integration test assertions.
