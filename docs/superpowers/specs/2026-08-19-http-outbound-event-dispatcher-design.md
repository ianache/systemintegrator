# Spec: HTTP Outbound Event Dispatcher (Kafka to Target API)

## 1. Context & Problem Statement
The integration platform extracts records from external databases (e.g. SIGO / SAP HANA), transforms them, and reliably stages them as domain events into Apache Kafka (`integration.events`) via the Transactional Outbox pattern.

Currently, there is no component actively consuming events from `integration.events` to dispatch them to target external REST APIs or webhooks based on outbound integration profiles.

## 2. Goals & Non-Goals
- **Goals**:
  - Consume events from `integration.events` idempotently using the existing `KafkaInboxListener` and `InboxProcessor` (`integration_inbox` table).
  - Dynamically discover matching active integration profiles for the event's `tenantId` and `businessDomain` where `syncDirection` is `OUTBOUND` or `BIDIRECTIONAL` and `protocol` is `REST` (or `HTTP`).
  - Resolve endpoint credentials from HashiCorp Vault using `SecretResolver`.
  - Transform event payloads if outbound `mapping` or `transformation` (JSLT) rules are configured in the profile.
  - Execute HTTP POST requests to the target endpoint with configured timeout and resilience policies (`retryPolicy` and `rateLimitPolicy`).
  - If delivery fails after exhausting retries, mark the event as `DEAD_LETTER` in `integration_inbox` and route the failed message to `integration.events.dlq` via `DeadLetterQueuePublisher`.
- **Non-Goals**:
  - Processing JDBC-target batch synchronizations in this component (focused on HTTP/REST targets).

## 3. Architecture & Detailed Design

### 3.1 Component Architecture

```
[Kafka Topic: integration.events]
              │
              ▼
    KafkaInboxListener
              │
              ▼
       InboxProcessor (Idempotency check in integration_inbox)
              │
              ▼
  OutboundEventDispatcher (Domain Handler)
              │
   ├─ 1. Look up active profiles: (tenantId, businessDomain, direction in [OUTBOUND, BIDIRECTIONAL], protocol == REST)
   ├─ 2. Resolve credentials in Vault (SecretResolver)
   ├─ 3. Transform payload to target format (TransformationService)
   ├─ 4. Send HTTP POST via RestClient with ResilienceExecutor
              │
              ├── [Success 2xx] ──► Mark PROCESSED in integration_inbox
              │
              └── [Exhausted Failure] ──► Mark DEAD_LETTER in integration_inbox + Publish to integration.events.dlq
```

### 3.2 Key Components

1. **`HttpOutboundClient` (Adapter Out)**:
   - Encapsulates HTTP execution against external endpoints using Spring `RestClient`.
   - Injects authentication headers based on `ResolvedSecret` (e.g., `Authorization: Bearer ...` or Basic Auth `Authorization: Basic ...` or Custom API Key header).
   - Configurable connection/read timeouts.

2. **`OutboundEventDispatcher` (Application Service)**:
   - Receives `(UUID eventId, UUID tenantId, String eventType, String payload)`.
   - Derives `businessDomain` from `eventType` or headers (e.g., `customer.upserted` -> `customers`, `vehicle.upserted` -> `vehicles`).
   - Queries `IntegrationProfileRepository.findAll(tenantId, true)` filtering by `OUTBOUND`/`BIDIRECTIONAL` and protocol `REST`.
   - Iterates matching profiles, transforms payload, and calls `HttpOutboundClient` wrapped in `ResilienceExecutor`.

3. **`KafkaInboxListener` & `InboxProcessor`**:
   - Delegates incoming messages to `OutboundEventDispatcher::dispatch`.
   - On unhandled exception from dispatcher, `InboxProcessor` catches it, writes `DEAD_LETTER` status to `integration_inbox`, and publishes to `integration.events.dlq`.

4. **Configuration Properties**:
   - `integration.inbox.listener.auto-startup: true` (or environment-driven).
   - `integration.inbox.topics: integration.events`.
   - `integration.inbox.dlq.topic: integration.events.dlq`.

## 4. Test Strategy
- **Unit Tests**:
  - `HttpOutboundClientTest`: Verifies HTTP request serialization, auth headers injection (Bearer, Basic, API Key), and error response status handling.
  - `OutboundEventDispatcherTest`: Verifies profile lookup for tenant and domain, payload transformation, and multi-profile dispatching.
- **Integration Tests**:
  - `OutboundEventDispatchIntegrationTest`: Uses WireMock to simulate external target API and verifies end-to-end flow from Kafka consumer -> Inbox store -> WireMock target -> status PROCESSED in DB.
  - DLQ scenario test: When target responds with 500 Server Error, verifies retries, `DEAD_LETTER` DB status, and DLQ message publication.
