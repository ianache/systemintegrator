# Domain-Segregated Kafka Event Topics & Bidirectional Anti-Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route inbound synchronization domain events to domain-segregated Kafka topics (`integration.<businessDomain>.events`), include provenance headers (`X-External-Source`, `X-Business-Domain`), subscribe dynamically in Inbox listener via pattern matching, and prevent infinite ping-pong loops in bidirectional outbound dispatching.

**Architecture:** Transactional Outbox saves domain-specific topic per profile; Kafka Outbox Relay attaches origin and domain headers; Kafka Inbox Listener subscribes using wildcard topic pattern; Outbound Event Dispatcher filters out targets matching the event's origin external source.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring Kafka, Spring Data JPA, MySQL 8.4, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Domain events topic format: `integration.<businessDomain>.events` (lowercase domain).
- Mandatory Kafka Headers: `X-Tenant-ID`, `X-Aggregate-ID`, `X-Event-Type`, `X-Business-Domain`, `X-External-Source`, `X-Profile-ID`.
- Inbox listener must dynamically subscribe via `topicPattern = "${integration.inbox.topic-pattern:integration\\..*\\.events}"`.
- Outbound Event Dispatcher must ignore profiles where `profile.externalSource().equalsIgnoreCase(originExternalSource)`.

---

### Task 1: Update Outbox Event Topic Derivation in IntegrationSyncOrchestrator

**Files:**
- Modify: `src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java`
- Test: `src/test/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestratorTest.java`

**Interfaces:**
- Consumes: `IntegrationProfile.businessDomain()`, `OutboxEvent.pending(tenantId, aggregateId, aggregateType, eventType, topic, payload)`
- Produces: `OutboxEvent` with `topic = "integration." + profile.businessDomain().trim().toLowerCase() + ".events"`

- [ ] **Step 1: Write failing test in IntegrationSyncOrchestratorTest**

Verify that `OutboxEvent` has topic `integration.customers.events` for customers domain and `integration.units.events` for units domain.

```java
@Test
void derivesDomainSpecificTopicForOutboxEvent() throws Exception {
    String extractionConfigJson = "{\"query\":\"SELECT card_code, updated_at FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
            + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"card_code\",\"watermarkColumn\":\"updated_at\"}";
    IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\"}");

    ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
    when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
    when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

    HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
    when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(dataSource);

    Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
    List<Map<String, Object>> rows = List.of(Map.of("card_code", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp)));
    when(genericJdbcAdapter.extract(any(), any(), eq(Instant.EPOCH))).thenReturn(rows);
    when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

    orchestrator.run(profile);

    ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(outboxCaptor.capture());
    assertThat(outboxCaptor.getValue().topic()).isEqualTo("integration.customers.events");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dtest=IntegrationSyncOrchestratorTest#derivesDomainSpecificTopicForOutboxEvent"`
Expected: FAIL (topic was "integration.events" instead of "integration.customers.events")

- [ ] **Step 3: Update IntegrationSyncOrchestrator implementation**

In `IntegrationSyncOrchestrator.java`:
```java
String aggregateType = deriveAggregateType(profile.businessDomain());
String eventType = deriveEventType(profile.businessDomain());
String topic = deriveTopic(profile.businessDomain());

Instant maxRowTimestamp = watermark;
for (Map<String, Object> row : rows) {
    String rowJson = objectMapper.writeValueAsString(row);
    String canonicalJson = transformationService.transform(rowJson, profile);
    UUID aggregateId = deriveAggregateId(profile.tenantId(), String.valueOf(row.get(extractionConfig.keyColumn())));
    outboxRepository.save(OutboxEvent.pending(profile.tenantId(), aggregateId, aggregateType, eventType, topic, canonicalJson));

    Instant rowTimestamp = readWatermarkTimestamp(row, extractionConfig.watermarkColumn());
    if (rowTimestamp.isAfter(maxRowTimestamp)) {
        maxRowTimestamp = rowTimestamp;
    }
}
```

Add helper:
```java
private String deriveTopic(String businessDomain) {
    if (businessDomain == null || businessDomain.isBlank()) {
        return "integration.events";
    }
    return "integration." + businessDomain.trim().toLowerCase() + ".events";
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test "-Dtest=IntegrationSyncOrchestratorTest"`
Expected: PASS

---

### Task 2: Enhance KafkaOutboxPublisher with Provenance Headers

**Files:**
- Modify: `src/main/java/com/cl2/integration/integration/outbox/KafkaOutboxPublisher.java`
- Modify: `src/main/java/com/cl2/integration/integration/outbox/OutboxJpaEntity.java` (if helper needed for metadata)
- Test: `src/test/java/com/cl2/integration/integration/outbox/OutboxEntityTest.java`

**Interfaces:**
- Consumes: `OutboxJpaEntity`, `KafkaTemplate`
- Produces: Kafka `ProducerRecord` with `X-Business-Domain`, `X-Event-Type`, `X-Tenant-ID`, `X-Aggregate-ID`

- [ ] **Step 1: Write/Update unit test for Kafka record headers**

Verify headers on produced record in `OutboxEntityTest.java` / `KafkaOutboxPublisherTest`.

- [ ] **Step 2: Update KafkaOutboxPublisher**

In `KafkaOutboxPublisher.java`:
```java
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
    if (event.toDomain().aggregateType() != null) {
        record.headers().add(new RecordHeader("X-Business-Domain", event.toDomain().aggregateType().getBytes(StandardCharsets.UTF_8)));
    }
    return kafkaTemplate.send(record);
}
```

- [ ] **Step 3: Run unit tests**

Run: `mvn test "-Dtest=OutboxEntityTest"`
Expected: PASS

---

### Task 3: Update KafkaInboxListener for Dynamic Topic Pattern Subscription

**Files:**
- Modify: `src/main/java/com/cl2/integration/integration/inbox/KafkaInboxListener.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/cl2/integration/integration/inbox/KafkaInboxListenerTest.java`

**Interfaces:**
- Consumes: Kafka messages matching topic pattern `integration\..*\.events`
- Produces: `inboxProcessor.process(...)` and `outboundEventDispatcher.dispatch(eventId, tenantId, eventType, payload, originExternalSource)`

- [x] **Step 1: Write failing test in KafkaInboxListenerTest**

Verify that `KafkaInboxListener` extracts `X-External-Source` and passes it to `OutboundEventDispatcher`.

- [x] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dtest=KafkaInboxListenerTest"`
Expected: FAIL

- [x] **Step 3: Update KafkaInboxListener and application.yml**

In `KafkaInboxListener.java`:
```java
@KafkaListener(topicPattern = "${integration.inbox.topic-pattern:integration\\..*\\.events}", groupId = "${spring.kafka.consumer.group-id:integration-consumer-group}", autoStartup = "${integration.inbox.listener.auto-startup:false}")
public void onMessage(ConsumerRecord<String, String> record) {
    UUID eventId;
    try {
        eventId = UUID.fromString(record.key());
    } catch (Exception ex) {
        eventId = UUID.randomUUID();
    }
    final UUID finalEventId = eventId;

    UUID rawTenantId = extractHeaderAsUuid(record, "X-Tenant-ID");
    final UUID tenantId = rawTenantId != null ? rawTenantId : UUID.fromString("00000000-0000-0000-0000-000000000000");

    String eventType = extractHeaderAsString(record, "X-Event-Type", "UnknownEvent");
    String externalSource = extractHeaderAsString(record, "X-External-Source", null);
    log.info("Received event in KafkaInboxListener: eventId={}, tenantId={}, topic={}, source={}", eventId, tenantId, record.topic(), externalSource);

    inboxProcessor.process(eventId, tenantId, eventType, record.value(), record.topic(), payload -> {
        outboundEventDispatcher.dispatch(finalEventId, tenantId, eventType, payload, externalSource);
    });
}
```

In `src/main/resources/application.yml`:
```yaml
integration:
  inbox:
    topic-pattern: ${INTEGRATION_INBOX_TOPIC_PATTERN:integration\..*\.events}
    topics: ${INTEGRATION_INBOX_TOPICS:integration.events}
    listener:
      auto-startup: ${INTEGRATION_INBOX_LISTENER_AUTO_STARTUP:false}
```

- [x] **Step 4: Run test to verify it passes**

Run: `mvn test "-Dtest=KafkaInboxListenerTest"`
Expected: PASS

---

### Task 4: Implement Anti-Loop Protection in OutboundEventDispatcher

**Files:**
- Modify: `src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java`
- Test: `src/test/java/com/cl2/integration/integration/outbound/OutboundEventDispatchIntegrationTest.java`

**Interfaces:**
- Consumes: `dispatch(UUID eventId, UUID tenantId, String eventType, String payload, String originExternalSource)`
- Produces: Dispatches only to profiles where `originExternalSource == null || !originExternalSource.equalsIgnoreCase(profile.externalSource())`

- [x] **Step 1: Write failing test in OutboundEventDispatchIntegrationTest**

Add test checking that an event originated from `"sigo"` does not trigger dispatch to a profile with `externalSource = "sigo"`.

- [x] **Step 2: Run test to verify failure**

Run: `mvn test "-Dtest=OutboundEventDispatchIntegrationTest"`
Expected: FAIL

- [x] **Step 3: Update OutboundEventDispatcher implementation**

In `OutboundEventDispatcher.java`:
```java
public void dispatch(UUID eventId, UUID tenantId, String eventType, String payload) {
    dispatch(eventId, tenantId, eventType, payload, null);
}

public void dispatch(UUID eventId, UUID tenantId, String eventType, String payload, String originExternalSource) {
    if (tenantId == null) {
        log.warn("Cannot dispatch outbound event: tenantId is null (eventId={}, eventType={})", eventId, eventType);
        return;
    }

    String derivedDomain = deriveBusinessDomain(eventType);
    log.debug("Dispatching outbound event: eventId={}, tenantId={}, eventType={}, derivedDomain={}, originSource={}",
            eventId, tenantId, eventType, derivedDomain, originExternalSource);

    List<IntegrationProfile> activeProfiles = profileRepository.findAll(tenantId, true);
    if (activeProfiles == null || activeProfiles.isEmpty()) {
        log.info("No active integration profiles configured for tenantId={}", tenantId);
        return;
    }

    List<IntegrationProfile> matchingProfiles = activeProfiles.stream()
            .filter(profile -> isOutboundRestProfile(profile))
            .filter(profile -> matchesBusinessDomain(profile.businessDomain(), derivedDomain, eventType))
            .filter(profile -> originExternalSource == null || !originExternalSource.equalsIgnoreCase(profile.externalSource()))
            .toList();

    if (matchingProfiles.isEmpty()) {
        log.info("No matching active outbound REST profiles found for tenantId={}, eventType={}, derivedDomain={}, originSource={}",
                tenantId, eventType, derivedDomain, originExternalSource);
        return;
    }

    for (IntegrationProfile profile : matchingProfiles) {
        dispatchToProfile(eventId, tenantId, payload, profile);
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `mvn test "-Dtest=OutboundEventDispatchIntegrationTest"`
Expected: PASS

---

### Task 5: Full Test Suite Verification & Docker Rebuild

**Files:**
- Test: All unit & integration tests
- Config: `compose.yaml`

- [ ] **Step 1: Run complete Maven test suite**

Run: `mvn test`
Expected: BUILD SUCCESS (all tests passing)

- [ ] **Step 2: Rebuild Docker containers and verify**

Run: `docker compose up -d --build`
Expected: Containers healthy, new events published to `integration.unidades.events` upon sync.
