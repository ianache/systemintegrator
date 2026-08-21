# Profile Deactivation Unfinished Executions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Handle running executions and pending outbox events when an Integration Profile is deactivated, canceling active sync tasks, setting `SyncState` to `CANCELLED` without corrupting the watermark, and canceling pending outbox records.

**Architecture:** Domain event listener (`ProfileDeactivationHandler`) triggered on `IntegrationProfileDeactivated` event that interrupts active thread execution via `IntegrationSyncService`, updates `integration_sync_state` to `CANCELLED`, and cancels all `PENDING` events in `integration_outbox` for the profile's tenant and topic.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data JPA, MySQL 8.4, JUnit 5, Mockito, AssertJ.

## Global Constraints
- Do not advance or corrupt `last_watermark` when an execution is cancelled.
- Maintain cooperative thread interruption checking in `IntegrationSyncOrchestrator`.
- Transact rollback on `SyncExecutionCancelledException` during orchestrator run.
- All unit and integration tests must pass (`mvn test`).

---

### Task 1: Extend `SyncRunStatus` and `SpringDataOutboxRepository` for Cancellation

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/integration/sync/SyncRunStatus.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/outbox/SpringDataOutboxRepository.java`
- Test: `application/src/test/java/com/cl2/integration/integration/outbox/SpringDataOutboxRepositoryTest.java`

**Interfaces:**
- Produces:
  - `SyncRunStatus.CANCELLED`
  - `int cancelPendingByTenantAndTopic(UUID tenantId, String topic, String errorReason)`

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.integration.outbox;

import com.cl2.integration.IntegrationApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpringDataOutboxRepositoryTest extends IntegrationApplicationTest {

    @Autowired
    private SpringDataOutboxRepository repository;

    @Test
    @DisplayName("Should cancel pending outbox events for a given tenant and topic")
    void shouldCancelPendingEventsByTenantAndTopic() {
        UUID tenantId = UUID.randomUUID();
        String topic = "integration.units.events";

        OutboxEvent event1 = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"id\":1}");
        OutboxEvent event2 = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"id\":2}");
        OutboxEvent otherTenant = OutboxEvent.pending(UUID.randomUUID(), UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"id\":3}");
        OutboxEvent otherTopic = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Customer", "customers.created", "integration.customers.events", "{\"id\":4}");

        repository.save(OutboxJpaEntity.from(event1));
        repository.save(OutboxJpaEntity.from(event2));
        repository.save(OutboxJpaEntity.from(otherTenant));
        repository.save(OutboxJpaEntity.from(otherTopic));

        int cancelledCount = repository.cancelPendingByTenantAndTopic(tenantId, topic, "Profile deactivated");

        assertThat(cancelledCount).isEqualTo(2);

        OutboxJpaEntity entity1 = repository.findById(event1.id()).orElseThrow();
        assertThat(entity1.toDomain().status()).isEqualTo(OutboxStatus.CANCELLED);
        assertThat(entity1.toDomain().lastError()).isEqualTo("Profile deactivated");

        OutboxJpaEntity entity2 = repository.findById(event2.id()).orElseThrow();
        assertThat(entity2.toDomain().status()).isEqualTo(OutboxStatus.CANCELLED);

        OutboxJpaEntity otherTenantEntity = repository.findById(otherTenant.id()).orElseThrow();
        assertThat(otherTenantEntity.toDomain().status()).isEqualTo(OutboxStatus.PENDING);

        OutboxJpaEntity otherTopicEntity = repository.findById(otherTopic.id()).orElseThrow();
        assertThat(otherTopicEntity.toDomain().status()).isEqualTo(OutboxStatus.PENDING);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl application "-Dtest=SpringDataOutboxRepositoryTest"`
Expected: FAIL with compilation error (cannot find `OutboxStatus.CANCELLED` or method `cancelPendingByTenantAndTopic`).

- [ ] **Step 3: Write minimal implementation**

Update `SyncRunStatus.java`:
```java
package com.cl2.integration.integration.sync;

public enum SyncRunStatus {
    SUCCESS,
    FAILED,
    CANCELLED
}
```

Update `OutboxStatus.java` (if needed, ensure `CANCELLED` is present):
```java
package com.cl2.integration.integration.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
    DEAD_LETTER,
    CANCELLED
}
```

Update `SpringDataOutboxRepository.java`:
```java
package com.cl2.integration.integration.outbox;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataOutboxRepository extends Repository<OutboxJpaEntity, UUID> {
    OutboxJpaEntity save(OutboxJpaEntity entity);
    Optional<OutboxJpaEntity> findById(UUID id);

    @Query(value = "SELECT * FROM integration_outbox WHERE status = 'PENDING' AND available_at <= :now ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxJpaEntity> findPendingForPublishing(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = "UPDATE integration_outbox SET status = 'CANCELLED', last_error = :errorReason WHERE tenant_id = :tenantId AND topic = :topic AND status = 'PENDING'", nativeQuery = true)
    int cancelPendingByTenantAndTopic(@Param("tenantId") UUID tenantId, @Param("topic") String topic, @Param("errorReason") String errorReason);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl application "-Dtest=SpringDataOutboxRepositoryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/integration/sync/SyncRunStatus.java application/src/main/java/com/cl2/integration/integration/outbox/OutboxStatus.java application/src/main/java/com/cl2/integration/integration/outbox/SpringDataOutboxRepository.java application/src/test/java/com/cl2/integration/integration/outbox/SpringDataOutboxRepositoryTest.java
git commit -m "feat(outbox): add CANCELLED status and batch cancellation query by tenant and topic"
```

---

### Task 2: Active Execution Tracking & Cooperative Cancellation in `IntegrationSyncService` and `IntegrationSyncOrchestrator`

**Files:**
- Create: `application/src/main/java/com/cl2/integration/integration/sync/SyncExecutionCancelledException.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncService.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/sync/SyncStateRecorder.java`
- Test: `application/src/test/java/com/cl2/integration/integration/sync/IntegrationSyncCancellationTest.java`

**Interfaces:**
- Produces:
  - `IntegrationSyncService.cancelRunningExecution(UUID profileId)`
  - `SyncStateRecorder.recordCancelled(UUID profileId, Instant startedAt, String reason)`

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IntegrationSyncCancellationTest {

    @Test
    @DisplayName("Should cancel active future when cancelRunningExecution is invoked")
    void shouldCancelActiveFuture() throws Exception {
        IntegrationSyncOrchestrator orchestrator = mock(IntegrationSyncOrchestrator.class);
        SyncStateRepository syncStateRepository = mock(SyncStateRepository.class);
        IntegrationSyncProperties properties = new IntegrationSyncProperties();

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);

        doAnswer(invocation -> {
            taskStarted.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(orchestrator).run(any());

        IntegrationSyncService service = new IntegrationSyncService(
                mock(com.cl2.integration.domain.port.IntegrationProfileRepository.class),
                orchestrator,
                (runnable, lockConfig) -> runnable.run(),
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                properties
        );

        UUID profileId = UUID.randomUUID();
        IntegrationProfile profile = IntegrationProfile.create(
                profileId,
                UUID.randomUUID(),
                "units",
                "sigo",
                SyncDirection.INBOUND,
                SourceOfTruth.SOURCE,
                new IntegrationProfileConfiguration(
                        IntegrationProtocol.JDBC, "generic-jdbc-connector", "generic-jdbc-adapter",
                        "jdbc:mysql://localhost", "cred-ref", null, null, null, null, null,
                        "{\"watermarkColumn\":\"updated_at\",\"keyColumn\":\"id\"}"
                )
        );

        service.dispatch(profile);
        assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();

        service.cancelRunningExecution(profileId);
        assertThat(taskInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl application "-Dtest=IntegrationSyncCancellationTest"`
Expected: FAIL (method `cancelRunningExecution` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `SyncExecutionCancelledException.java`:
```java
package com.cl2.integration.integration.sync;

public class SyncExecutionCancelledException extends RuntimeException {
    public SyncExecutionCancelledException(String message) {
        super(message);
    }
}
```

Update `SyncStateRecorder.java` to support recording `CANCELLED`:
```java
package com.cl2.integration.integration.sync;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class SyncStateRecorder {

    private final SyncStateRepository syncStateRepository;

    public SyncStateRecorder(SyncStateRepository syncStateRepository) {
        this.syncStateRepository = syncStateRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID profileId, Instant watermark, Instant startedAt) {
        syncStateRepository.save(new SyncState(profileId, watermark, startedAt, SyncRunStatus.SUCCESS, null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID profileId, Instant startedAt, String error) {
        Instant currentWatermark = syncStateRepository.find(profileId)
                .map(SyncState::lastWatermark)
                .orElse(Instant.EPOCH);
        syncStateRepository.save(new SyncState(profileId, currentWatermark, startedAt, SyncRunStatus.FAILED, error));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCancelled(UUID profileId, Instant startedAt, String reason) {
        Instant currentWatermark = syncStateRepository.find(profileId)
                .map(SyncState::lastWatermark)
                .orElse(Instant.EPOCH);
        syncStateRepository.save(new SyncState(profileId, currentWatermark, startedAt, SyncRunStatus.CANCELLED, reason));
    }
}
```

Update `IntegrationSyncService.java`:
```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

@Service
public class IntegrationSyncService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncService.class);

    private final IntegrationProfileRepository profileRepository;
    private final IntegrationSyncOrchestrator orchestrator;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Executor integrationSyncExecutor;
    private final IntegrationSyncProperties properties;
    private final Map<UUID, Future<?>> activeExecutions = new ConcurrentHashMap<>();

    public IntegrationSyncService(
            IntegrationProfileRepository profileRepository,
            IntegrationSyncOrchestrator orchestrator,
            LockingTaskExecutor lockingTaskExecutor,
            @Qualifier("integrationSyncExecutor") Executor integrationSyncExecutor,
            IntegrationSyncProperties properties) {
        this.profileRepository = profileRepository;
        this.orchestrator = orchestrator;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.integrationSyncExecutor = integrationSyncExecutor;
        this.properties = properties;
    }

    public void triggerSync(UUID tenantId, UUID profileId) {
        IntegrationProfile profile = profileRepository.findById(tenantId, profileId);
        if (profile == null) {
            throw new IntegrationProfileNotFoundException("Integration profile was not found: " + profileId);
        }
        if (!profile.active()) {
            throw new IllegalStateException("Integration profile is inactive: " + profileId);
        }
        dispatch(profile);
    }

    public void dispatch(IntegrationProfile profile) {
        LockConfiguration lockConfiguration = new LockConfiguration(
                Instant.now(), "sync:" + profile.id(),
                Duration.ofSeconds(properties.getDefaultRunLockAtMostForSeconds()), Duration.ofSeconds(1));
        Runnable task = () -> orchestrator.run(profile);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                lockingTaskExecutor.executeWithLock(task, lockConfiguration);
            } catch (Exception ex) {
                log.warn("Sync run failed for profile {}: {}", profile.id(), ex.getMessage());
            } finally {
                activeExecutions.remove(profile.id());
            }
        }, integrationSyncExecutor);

        activeExecutions.put(profile.id(), future);
    }

    public void cancelRunningExecution(UUID profileId) {
        Future<?> future = activeExecutions.remove(profileId);
        if (future != null && !future.isDone()) {
            log.info("Canceling active sync execution for profileId={}", profileId);
            future.cancel(true);
        }
    }
}
```

Update `IntegrationSyncOrchestrator.java` to check interruption during rows loop:
```java
// Inside rows loop in IntegrationSyncOrchestrator.java:
for (Map<String, Object> row : rows) {
    if (Thread.currentThread().isInterrupted()) {
        log.info("Sync execution interrupted for profileId={} (tenantId={})", profile.id(), profile.tenantId());
        throw new SyncExecutionCancelledException("Execution was cancelled for profile " + profile.id());
    }
    String rowJson = objectMapper.writeValueAsString(row);
    // ...
}
```
And handle `SyncExecutionCancelledException` in `catch`:
```java
} catch (SyncExecutionCancelledException ex) {
    syncStateRecorder.recordCancelled(profile.id(), startedAt, ex.getMessage());
    throw ex;
} catch (Exception ex) {
    syncStateRecorder.recordFailure(profile.id(), startedAt, ex.getMessage());
    throw ex;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl application "-Dtest=IntegrationSyncCancellationTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/integration/sync/ application/src/test/java/com/cl2/integration/integration/sync/IntegrationSyncCancellationTest.java
git commit -m "feat(sync): add cooperative cancellation tracking in IntegrationSyncService and Orchestrator"
```

---

### Task 3: Profile Deactivation Event Handler (`ProfileDeactivationHandler`)

**Files:**
- Create: `application/src/main/java/com/cl2/integration/integration/profile/ProfileDeactivationHandler.java`
- Test: `application/src/test/java/com/cl2/integration/integration/profile/ProfileDeactivationHandlerTest.java`

**Interfaces:**
- Consumes:
  - `IntegrationProfileEvent("IntegrationProfileDeactivated")`
- Produces:
  - Invokes `IntegrationSyncService.cancelRunningExecution`
  - Invokes `SpringDataOutboxRepository.cancelPendingByTenantAndTopic`
  - Invokes `SyncStateRecorder.recordCancelled`

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.integration.profile;

import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import com.cl2.integration.integration.sync.IntegrationSyncService;
import com.cl2.integration.integration.sync.SyncStateRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ProfileDeactivationHandlerTest {

    @Test
    @DisplayName("Should cancel active sync and pending outbox events on IntegrationProfileDeactivated event")
    void shouldHandleProfileDeactivation() {
        IntegrationSyncService syncService = mock(IntegrationSyncService.class);
        SpringDataOutboxRepository outboxRepository = mock(SpringDataOutboxRepository.class);
        SyncStateRecorder syncStateRecorder = mock(SyncStateRecorder.class);

        ProfileDeactivationHandler handler = new ProfileDeactivationHandler(
                syncService,
                outboxRepository,
                syncStateRecorder
        );

        UUID profileId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        IntegrationProfileView view = new IntegrationProfileView(
                profileId, tenantId, "units", "sigo", SyncDirection.INBOUND,
                SourceOfTruth.SOURCE, new IntegrationProfileConfiguration(IntegrationProtocol.JDBC, "connector", "adapter", "url", "ref", null, null, null, null, null, null),
                false, Instant.now(), Instant.now(), 1L
        );

        IntegrationProfileEvent event = new IntegrationProfileEvent(
                UUID.randomUUID(), "IntegrationProfileDeactivated", profileId, tenantId, Instant.now(), view
        );

        handler.onProfileDeactivated(event);

        verify(syncService).cancelRunningExecution(profileId);
        verify(outboxRepository).cancelPendingByTenantAndTopic(tenantId, "integration.units.events", "Profile deactivated");
        verify(syncStateRecorder).recordCancelled(eq(profileId), any(), eq("Profile deactivated"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl application "-Dtest=ProfileDeactivationHandlerTest"`
Expected: FAIL (class `ProfileDeactivationHandler` not found).

- [ ] **Step 3: Write minimal implementation**

Create `ProfileDeactivationHandler.java`:
```java
package com.cl2.integration.integration.profile;

import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import com.cl2.integration.integration.sync.IntegrationSyncService;
import com.cl2.integration.integration.sync.SyncStateRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ProfileDeactivationHandler {

    private static final Logger log = LoggerFactory.getLogger(ProfileDeactivationHandler.class);

    private final IntegrationSyncService syncService;
    private final SpringDataOutboxRepository outboxRepository;
    private final SyncStateRecorder syncStateRecorder;

    public ProfileDeactivationHandler(
            IntegrationSyncService syncService,
            SpringDataOutboxRepository outboxRepository,
            SyncStateRecorder syncStateRecorder) {
        this.syncService = syncService;
        this.outboxRepository = outboxRepository;
        this.syncStateRecorder = syncStateRecorder;
    }

    @EventListener
    public void onProfileDeactivated(IntegrationProfileEvent event) {
        if (event == null || !"IntegrationProfileDeactivated".equalsIgnoreCase(event.eventType())) {
            return;
        }

        log.info("Handling profile deactivation for profileId={}, tenantId={}", event.profileId(), event.tenantId());

        // 1. Cancel in-memory running execution if active
        syncService.cancelRunningExecution(event.profileId());

        // 2. Cancel pending outbox events for this profile's domain/topic
        if (event.state() != null && event.state().businessDomain() != null) {
            String topic = "integration." + event.state().businessDomain().trim().toLowerCase() + ".events";
            int cancelled = outboxRepository.cancelPendingByTenantAndTopic(event.tenantId(), topic, "Profile deactivated");
            log.info("Cancelled {} pending outbox event(s) for topic={}", cancelled, topic);
        }

        // 3. Mark sync state as CANCELLED
        syncStateRecorder.recordCancelled(event.profileId(), Instant.now(), "Profile deactivated");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl application "-Dtest=ProfileDeactivationHandlerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/integration/profile/ProfileDeactivationHandler.java application/src/test/java/com/cl2/integration/integration/profile/ProfileDeactivationHandlerTest.java
git commit -m "feat(profile): create ProfileDeactivationHandler for cancellation and outbox discarding"
```

---

### Task 4: End-to-End Integration Test for Profile Deactivation Flow

**Files:**
- Create: `application/src/test/java/com/cl2/integration/integration/profile/ProfileDeactivationIntegrationTest.java`
- Test: All tests via `mvn test`

**Interfaces:**
- Verifies complete lifecycle: API `POST /api/v1/integration-profiles/{id}/deactivate` -> event publish -> task cancelled -> outbox cancelled -> sync state updated -> outbox relay skips cancelled records.

- [ ] **Step 1: Write integration test**

```java
package com.cl2.integration.integration.profile;

import com.cl2.integration.IntegrationApplicationTest;
import com.cl2.integration.application.IntegrationProfileService;
import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxJpaEntity;
import com.cl2.integration.integration.outbox.OutboxRelayScheduler;
import com.cl2.integration.integration.outbox.OutboxStatus;
import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import com.cl2.integration.integration.sync.SyncRunStatus;
import com.cl2.integration.integration.sync.SyncState;
import com.cl2.integration.integration.sync.SyncStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileDeactivationIntegrationTest extends IntegrationApplicationTest {

    @Autowired
    private IntegrationProfileService profileService;

    @Autowired
    private SpringDataOutboxRepository outboxRepository;

    @Autowired
    private SyncStateRepository syncStateRepository;

    @Autowired
    private OutboxRelayScheduler outboxRelayScheduler;

    @Test
    @DisplayName("Should cancel pending outbox events and set sync state to CANCELLED on profile deactivation")
    void shouldCancelPendingOutboxEventsAndMarkStateOnDeactivation() {
        UUID tenantId = UUID.randomUUID();
        CreateIntegrationProfileCommand command = new CreateIntegrationProfileCommand(
                "units",
                "sigo-erp",
                SyncDirection.INBOUND,
                SourceOfTruth.SOURCE,
                new IntegrationProfileConfiguration(
                        IntegrationProtocol.JDBC, "connector", "adapter", "jdbc:mysql://localhost", "cred-ref",
                        null, null, null, null, null, "{\"watermarkColumn\":\"updated_at\",\"keyColumn\":\"id\"}"
                )
        );

        var created = profileService.create(tenantId, command);
        UUID profileId = created.id();

        String topic = "integration.units.events";
        OutboxEvent event1 = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"unit\":1}");
        OutboxEvent event2 = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"unit\":2}");
        outboxRepository.save(OutboxJpaEntity.from(event1));
        outboxRepository.save(OutboxJpaEntity.from(event2));

        profileService.deactivate(tenantId, profileId);

        OutboxJpaEntity e1 = outboxRepository.findById(event1.id()).orElseThrow();
        OutboxJpaEntity e2 = outboxRepository.findById(event2.id()).orElseThrow();
        assertThat(e1.toDomain().status()).isEqualTo(OutboxStatus.CANCELLED);
        assertThat(e2.toDomain().status()).isEqualTo(OutboxStatus.CANCELLED);

        SyncState syncState = syncStateRepository.find(profileId).orElseThrow();
        assertThat(syncState.lastRunStatus()).isEqualTo(SyncRunStatus.CANCELLED);
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn test -pl application "-Dtest=ProfileDeactivationIntegrationTest"`
Expected: PASS

- [ ] **Step 3: Run full reactor test suite**

Run: `mvn test`
Expected: 100% BUILD SUCCESS across all modules.

- [ ] **Step 4: Commit**

```bash
git add application/src/test/java/com/cl2/integration/integration/profile/ProfileDeactivationIntegrationTest.java
git commit -m "test(profile): add e2e integration test for profile deactivation cancellation flow"
```
