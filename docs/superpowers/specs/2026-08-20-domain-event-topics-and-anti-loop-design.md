# Design Spec: Domain-Segregated Kafka Event Topics & Bidirectional Anti-Loop Protection

- **Date:** 2026-08-20
- **Status:** Approved
- **Scope:** Messaging Architecture, Outbox Relay, Inbox Pattern, Dynamic Topic Routing, Bidirectional Synchronization Guardrails

---

## 1. Context & Objective

In the multitenant integration platform (PRD v2.0), inbound synchronization from external systems (such as SAP, SIGO, MySQL, REST) captures data changes and relays domain events through Apache Kafka using the Transactional Outbox pattern.

Previously, all inbound events were directed to a single generic topic `integration.events`. To align with Domain-Driven Design (DDD) best practices, tenant isolation, fine-grained access control, independent retention policies, and robust bidirectional synchronization without infinite ping-pong loops:
1. Domain events are routed to dedicated Kafka topics following the canonical convention: `integration.<businessDomain>.events` (e.g. `integration.unidades.events`, `integration.customers.events`).
2. The Outbox publisher attaches standardized provenance headers (`X-Tenant-ID`, `X-Event-Type`, `X-Aggregate-ID`, `X-Business-Domain`, `X-External-Source`, `X-Profile-ID`).
3. The Inbox listener dynamically subscribes to all domain event topics using topic pattern matching (`integration\\..*\\.events`).
4. The Outbound Event Dispatcher enforces anti-loop filtering to prevent echoing events back to the originating external system.

---

## 2. Architecture & Components

```
+-----------------------------------------------------------------------------------+
|                           INBOUND EXTRACTION / SYNC                               |
|                                                                                   |
|  [IntegrationSyncOrchestrator]                                                    |
|           |                                                                       |
|           v                                                                       |
|  [OutboxEvent] (topic = "integration." + profile.businessDomain().toLowerCase()   |
|                 + ".events", eventType = domain + ".upserted")                    |
|           |                                                                       |
|           v                                                                       |
|  [MySQL integration_outbox] (Transactional Commit)                                |
+-----------------------------------------------------------------------------------+
                                    |
                                    v
+-----------------------------------------------------------------------------------+
|                              OUTBOX RELAY PIPELINE                                |
|                                                                                   |
|  [OutboxRelayScheduler] -> [KafkaOutboxPublisher]                                 |
|           |                                                                       |
|           | Sends to Topic: integration.<businessDomain>.events                   |
|           | Headers:                                                              |
|           |   X-Tenant-ID: <tenantId>                                             |
|           |   X-Aggregate-ID: <aggregateId>                                       |
|           |   X-Event-Type: <businessDomain>.upserted                             |
|           |   X-Business-Domain: <businessDomain>                                 |
|           |   X-External-Source: <externalSource>                                 |
|           |   X-Profile-ID: <profileId>                                           |
|           v                                                                       |
|  [Apache Kafka Topics: integration.unidades.events, integration.customers.events] |
+-----------------------------------------------------------------------------------+
                                    |
                                    v
+-----------------------------------------------------------------------------------+
|                        INBOX & OUTBOUND DISPATCH ENGINE                           |
|                                                                                   |
|  [KafkaInboxListener] (@KafkaListener(topicPattern = "integration\\..*\\.events"))|
|           |                                                                       |
|           v                                                                       |
|  [InboxProcessor] (Deduplication, Idempotency, Status = RECEIVED -> PROCESSED)   |
|           |                                                                       |
|           v                                                                       |
|  [OutboundEventDispatcher]                                                        |
|           |                                                                       |
|           |-- Anti-Loop Rule: profile.externalSource != originExternalSource      |
|           v                                                                       |
|  [HttpOutboundClient] (Dispatch to other external systems or downstream APIs)     |
+-----------------------------------------------------------------------------------+
```

---

## 3. Technical Specifications

### 3.1 Outbox Topic Derivation & Event Creation
- **File:** `com.cl2.integration.integration.sync.IntegrationSyncOrchestrator`
- **Topic Formula:** `"integration." + profile.businessDomain().trim().toLowerCase() + ".events"`
- **Outbox Persistence:** Calls `OutboxEvent.pending(tenantId, aggregateId, aggregateType, eventType, topic, payload)` so that the derived topic is persisted in the `integration_outbox.topic` column.

### 3.2 Kafka Message Publishing & Provenance Headers
- **File:** `com.cl2.integration.integration.outbox.KafkaOutboxPublisher`
- When constructing `ProducerRecord<String, String>`, adds standard headers:
  - `X-Tenant-ID`: stringified UUID.
  - `X-Aggregate-ID`: stringified aggregate UUID.
  - `X-Event-Type`: e.g. `unidades.upserted`.
  - `X-Business-Domain`: derived from entity or event metadata.
  - `X-External-Source`: name of the external source system (e.g. `sigo`, `sap-hana`).

### 3.3 Dynamic Inbox Subscription
- **File:** `com.cl2.integration.integration.inbox.KafkaInboxListener`
- **Configuration:** Update `@KafkaListener` to support pattern matching:
  - `topicPattern = "${integration.inbox.topic-pattern:integration\\..*\\.events}"`
- **Header Extraction:** Extracts `X-External-Source` in addition to `X-Tenant-ID` and `X-Event-Type`.
- Passes the originating source to `OutboundEventDispatcher.dispatch(eventId, tenantId, eventType, payload, originExternalSource)`.

### 3.4 Bidirectional Anti-Loop Filter
- **File:** `com.cl2.integration.integration.outbound.OutboundEventDispatcher`
- Updated signature: `dispatch(UUID eventId, UUID tenantId, String eventType, String payload, String originExternalSource)`
- Filtering rule:
  ```java
  List<IntegrationProfile> matchingProfiles = activeProfiles.stream()
      .filter(this::isOutboundRestProfile)
      .filter(profile -> matchesBusinessDomain(profile.businessDomain(), derivedDomain, eventType))
      .filter(profile -> originExternalSource == null || !originExternalSource.equalsIgnoreCase(profile.externalSource()))
      .toList();
  ```

---

## 4. Verification & Testing Plan

1. **Unit Tests**:
   - `IntegrationSyncOrchestratorTest`: Verify outbox event is saved with topic `integration.<domain>.events`.
   - `KafkaOutboxPublisherTest`: Verify Kafka record headers contain `X-External-Source` and proper topic routing.
   - `OutboundEventDispatcherTest`: Verify that outbound dispatch skips profiles where `profile.externalSource() == originExternalSource`.
2. **Integration Tests**:
   - `IntegrationSyncEndToEndTest`: Verify full synchronization writes to domain-segregated Kafka topic.
   - `OutboxInboxFlowIntegrationTest`: Verify dynamic topic regex consumption by `KafkaInboxListener`.
3. **E2E Container Verification**:
   - Execute sync for `unidades` with external source `sigo`.
   - Verify Kafka topic `integration.unidades.events` exists and receives messages with correct headers.
