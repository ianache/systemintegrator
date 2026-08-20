# HTTP Outbound Event Dispatcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume events from `integration.events` via `KafkaInboxListener`, match active outbound profiles (`syncDirection: OUTBOUND/BIDIRECTIONAL`, `protocol: REST`), resolve credentials in Vault, transform payload, and dispatch via HTTP POST with resilience and DLQ handling.

**Architecture:** 
- `HttpOutboundClient`: Encapsulates REST API dispatching with auth headers and timeout configuration.
- `OutboundEventDispatcher`: Discovers matching profiles for `(tenantId, businessDomain)` and orchestrates transformation, resilience (`ResilienceExecutor`), and HTTP delivery.
- `KafkaInboxListener`: Connects `InboxProcessor` to `OutboundEventDispatcher`.

**Tech Stack:** Java 21, Spring Boot 3.4 (RestClient), Spring Kafka, Resilience4j, JUnit 5, AssertJ, WireMock.

## Global Constraints
- Kafka Topic: `integration.events`
- DLQ Topic: `integration.events.dlq`
- Profile filter: `active: true`, `protocol: REST`, `syncDirection` in `[OUTBOUND, BIDIRECTIONAL]`

---

### Task 1: Create `HttpOutboundClient`

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/out/http/HttpOutboundClient.java`
- Test: `src/test/java/com/cl2/integration/adapter/out/http/HttpOutboundClientTest.java`

- [ ] **Step 1: Write failing unit tests for `HttpOutboundClient`**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `HttpOutboundClient` with support for Basic Auth, Bearer token, and custom API key headers**
- [ ] **Step 4: Run test to verify it passes**

---

### Task 2: Implement `OutboundEventDispatcher`

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java`
- Test: `src/test/java/com/cl2/integration/integration/outbound/OutboundEventDispatcherTest.java`

- [ ] **Step 1: Write failing unit tests for `OutboundEventDispatcher`**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `OutboundEventDispatcher` (profile lookup by tenant + domain, TransformationService, SecretResolver, ResilienceExecutor, HttpOutboundClient)**
- [ ] **Step 4: Run test to verify it passes**

---

### Task 3: Wire `KafkaInboxListener` to `OutboundEventDispatcher` and Configure Auto-Startup

**Files:**
- Modify: `src/main/java/com/cl2/integration/integration/inbox/KafkaInboxListener.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/cl2/integration/integration/outbound/OutboundEventDispatchIntegrationTest.java`

- [ ] **Step 1: Update `KafkaInboxListener` to delegate to `OutboundEventDispatcher`**
- [ ] **Step 2: Write end-to-end integration test with WireMock verifying Kafka -> Inbox -> HTTP Target & DLQ on failure**
- [ ] **Step 3: Run full Maven test suite**
