# Design Spec: Integration Sync Scheduler (Dynamic Cron-per-Profile JDBC Pipeline)

- **Date:** 2026-08-18
- **Status:** Approved
- **Scope:** Dynamic, per-profile scheduled execution of JDBC integration profiles (SAP HANA Customer case first), from `credentialRef` resolution through canonical outbox publication and watermark tracking.

---

## 1. Context & Objective

The system already lets a tenant register an `IntegrationProfile` with a `syncPolicy.cronExpression` (e.g. "run every 10 minutes") and a `credentialRef` pointing at a Vault secret. Nothing currently executes that policy: the only prior attempt (`SapPullingScheduler`) was a stub with no logic and has been removed. Supporting pieces already exist in isolation but are not wired together:

- `SecretResolver` / `VaultSecretResolver` — resolves `credentialRef` to a `ResolvedSecret` (username/password), with TTL cache.
- `GenericJdbcAdapter` — runs a validated, watermark-parameterized `SELECT` given an already-built `NamedParameterJdbcTemplate`.
- `TransformationService` — applies a profile's `mapping`/`transformation` JSON to a single JSON payload.
- `OutboxRelayScheduler` / outbox tables — transactional outbox already publishes to Kafka.
- `ResilienceExecutor` — circuit breaker per `tenantId:connector`.
- `ShedLock` (`LockProvider` bean) — distributed locking, currently only used via the static `@SchedulerLock` annotation.

This spec wires these into a working pipeline: **discover due profiles across all tenants → resolve credentials → connect to the source DB → extract delta rows → transform to canonical shape → publish via the existing outbox → advance a per-profile watermark**, running on each profile's own cron schedule, tolerant to per-profile failures.

Out of scope (explicit YAGNI, revisit later if needed):
- REST/Kafka source protocols (only `protocol=JDBC` is wired; other protocols are ignored by the scheduler for now).
- Direct `Customer` persistence and a `GET /api/v1/customers` endpoint (pre-existing gap, unrelated to scheduling).
- `retryPolicy` / `rateLimitPolicy` profile fields (unused today; not exercised by the registered SAP HANA profile).
- Sub-second scheduling precision.

---

## 2. Component Architecture & Data Flow

```
        +-----------------------------------------------------------+
        |               IntegrationSyncScheduler (tick)              |
        |  @Scheduled(fixedDelay=30s) + @SchedulerLock (global,25s)  |
        +-----------------------------------------------------------+
                                   |
                    findAllActiveByProtocol(JDBC)  [all tenants]
                                   |
                for each profile: cron.next(lastRunStartedAt) <= now?
                                   |
                                   v  (per profile, async, own dynamic lock "sync:{profileId}")
        +-----------------------------------------------------------+
        |               IntegrationSyncOrchestrator.run(profile)     |
        +-----------------------------------------------------------+
           |                |                 |               |
           v                v                 v               v
   SecretResolver   JdbcDataSourceFactory  GenericJdbcAdapter  TransformationService
   (credentialRef)  (endpoint+secret ->    (SqlSecurityValidator (mapping/transformation
                      JdbcTemplate)         + extract, wrapped in   JSON, existing engine)
                                             ResilienceExecutor)
                                   |
                                   v
                    OutboxPersistenceAdapter.save(...)   +   SyncStateRepository.upsert(...)
                            (same @Transactional; both commit or neither does)
                                   |
                                   v
                    OutboxRelayScheduler (already exists) -> Kafka
```

### 2.1 New Domain/Port Types

```java
public record SyncState(
    UUID profileId,
    Instant lastWatermark,
    Instant lastRunStartedAt,
    SyncRunStatus lastRunStatus,   // SUCCESS | FAILED
    String lastError
) {}

public interface SyncStateRepository {
    Optional<SyncState> find(UUID profileId);
    void upsert(SyncState state);
}
```

```java
public interface IntegrationProfileRepository {
    // existing methods unchanged...
    List<IntegrationProfile> findAllActiveByProtocol(IntegrationProtocol protocol); // NEW, no tenantId filter
}
```

### 2.2 New Application Components

```java
@Component
public class IntegrationSyncScheduler {
    // @Scheduled(fixedDelay = 30000)
    // @SchedulerLock(name = "integration-sync-tick", lockAtMostFor = "25s")
    public void tick() { ... }
}

@Component
public class IntegrationSyncOrchestrator {
    public void run(IntegrationProfile profile) { ... } // called per due profile, own dynamic lock
}

@Component
public class JdbcDataSourceFactory {
    public NamedParameterJdbcTemplate create(String endpoint, ResolvedSecret secret) { ... }
}
```

---

## 3. Scheduling Mechanics

**Tick scanner, not per-profile triggers.** A single `@Scheduled(fixedDelay = 30000)` method runs every ~30s, protected by a *global* `@SchedulerLock("integration-sync-tick", lockAtMostFor="25s")` so only one node in the cluster scans at a time. Chosen over dynamic per-profile `TaskScheduler`/`CronTrigger` registration because:
- No lifecycle wiring needed when profiles are created/updated/deleted via the API — the next tick simply re-reads the current active set.
- Matches the existing polling pattern already used by `OutboxRelayScheduler`.
- ~30s tolerance is acceptable; nothing in the product requires sub-minute precision.

**Due-check per profile:**
```
cronExpr = CronExpression.parse(profile.syncPolicy.cronExpression)
state = syncStateRepository.find(profile.id)
anchor = state?.lastRunStartedAt ?? profile.createdAt
due = cronExpr.next(anchor) <= Instant.now()
```

**Locking granularity — two levels:**
1. Global tick lock (`"integration-sync-tick"`) — prevents two nodes from scanning/dispatching concurrently.
2. Per-profile dynamic lock (`"sync:" + profileId`), acquired directly via the injected `LockProvider` bean (not the static `@SchedulerLock` annotation, since the lock name is only known at runtime) — prevents a slow-running profile from overlapping with itself if its cron fires again before the previous run finishes. `lockAtMostFor` derived from `syncPolicy.overlapBufferSeconds` when present, default 10 minutes otherwise.

Each due profile's `orchestrator.run(profile)` is submitted to a dedicated `ThreadPoolTaskExecutor` bean (`integrationSyncExecutor`, small fixed pool, e.g. core size 4) so one slow/stuck profile does not delay the tick's evaluation of the remaining profiles. A dedicated executor (rather than `@Async`) keeps the concurrency bound explicit and avoids reliance on Spring AOP proxying for a background-only code path.

---

## 4. Execution Pipeline (`IntegrationSyncOrchestrator.run`)

```
1. secret = secretResolver.resolve(profile.credentialRef, profile.tenantId)
2. jdbcTemplate = jdbcDataSourceFactory.create(profile.endpoint, secret)
3. watermark = syncStateRepository.find(profile.id)?.lastWatermark ?? Instant.EPOCH
4. rows = resilienceExecutor.execute(profile.tenantId, profile.connector, () ->
              genericJdbcAdapter.extract(jdbcTemplate, extractionConfig, watermark))
5. for each row:
       json = objectMapper.writeValueAsString(row)
       canonical = transformationService.transform(json, profile)
       outboxPersistenceAdapter.save(OutboxEvent.of(profile, canonical))
6. newWatermark = max(row[extractionConfig.keyColumn/timestamp column]) - overlapBufferSeconds
7. syncStateRepository.upsert(SyncState(profile.id, newWatermark, startedAt, SUCCESS, null))
```

Steps 5–7 run inside a single `@Transactional` boundary shared with the outbox write, so a partial batch never advances the watermark without its rows being durably queued for publishing, and vice versa.

**Failure path:** any exception in steps 1–6 aborts the transaction (nothing in steps 5–7 commits). A *separate* `@Transactional(propagation = REQUIRES_NEW)` call records `syncStateRepository.upsert(profile.id, unchangedWatermark, startedAt, FAILED, ex.getMessage())` — same pattern `OutboxRelayScheduler` already uses for its own failure bookkeeping — so failures are visible even though the main transaction rolled back. Because the watermark did not advance, the next tick that finds the cron due will naturally retry from the same point; no separate retry policy is introduced.

The scheduler's per-profile loop catches all exceptions from `orchestrator.run(...)` so one broken profile (bad credential, unreachable HANA, lock contention) never stops the tick from evaluating the rest.

---

## 5. Data Model & Migration

New table, `V6__create_integration_sync_state.sql`:

```sql
CREATE TABLE integration_sync_state (
    profile_id           BINARY(16) PRIMARY KEY,
    last_watermark        TIMESTAMP(6) NULL,
    last_run_started_at   TIMESTAMP(6) NULL,
    last_run_status        VARCHAR(20) NULL,
    last_error             VARCHAR(1000) NULL,
    CONSTRAINT fk_sync_state_profile FOREIGN KEY (profile_id) REFERENCES integration_profile(id)
);
```

Kept as its own table (not a column on `integration_profile`) because it has a different write pattern (updated by the background job on every run) and lifecycle than the profile row (updated by tenant API edits with optimistic locking via `version`) — mixing the two would create lock contention between the scheduler and profile edits, and conflate "configuration" with "runtime state".

**Repository changes:**
- `SpringDataIntegrationProfileRepository`: add `findAllByActiveTrueAndProtocol(IntegrationProtocol protocol)` (no tenant filter — the scheduler is a system job, not a tenant-scoped request).
- `IntegrationProfileRepository` port: add `findAllActiveByProtocol(IntegrationProtocol protocol)`, translated the same way `IntegrationProfilePersistenceAdapter` already translates JPA entities to domain objects.
- New `SyncStateRepository` port + `SyncStateJpaEntity` / `SyncStatePersistenceAdapter`, following the same hexagonal pattern as `IntegrationProfileRepository`.

---

## 6. Error Handling & Resilience

- **Isolation:** per-profile `try/catch` in the scheduler tick; one failing profile never blocks others.
- **Lock contention:** if the dynamic per-profile lock can't be acquired (a previous run of the same profile is still in flight), the profile is skipped for this tick — standard ShedLock behavior, logged at DEBUG.
- **Circuit breaker:** reused as-is via `ResilienceExecutor.execute(tenantId, connector, ...)`. Accepted pre-existing limitation: two profiles in the same tenant sharing `connector="generic-jdbc"` but pointing at different SAP instances share one circuit breaker instance — not addressed here, out of scope.
- **No new retry policy:** `retryPolicy` stays unused, consistent with today; the natural retry is "the next tick where the cron is due", since a failed run never advances the watermark.
- **Secret/connection failures** (`SecretNotFoundException`, JDBC connect failure) are treated like any other pipeline failure — caught, logged, `FAILED` sync state recorded, watermark unchanged.

---

## 7. Testing Strategy

- **`IntegrationSyncSchedulerTest`** (unit, mocked repos) — cron-due detection across various `lastRunStartedAt`/`cronExpression` combinations; a throwing profile doesn't stop the scan of the rest.
- **`IntegrationSyncOrchestratorTest`** (unit, mocked `SecretResolver`, `JdbcDataSourceFactory`, `GenericJdbcAdapter`, `TransformationService`, outbox and sync-state repos) — happy path (outbox rows + watermark committed together); failure path (neither committed, `FAILED` state recorded).
- **`JdbcDataSourceFactoryTest`** — builds a real `NamedParameterJdbcTemplate` against the local test MySQL (same `application-test.yml` instance the rest of the suite already uses) from an endpoint URL + `ResolvedSecret`.
- **End-to-end test** (style of `IntegrationProfileEndToEndTest`) — registers a JDBC profile pointing at a scratch table in the local test MySQL (standing in for HANA, since no real HANA instance is available in CI), forces a tick, and asserts outbox rows were queued and `integration_sync_state` advanced.

---

## 8. Open Questions / Follow-ups (not blocking this implementation)

- REST and Kafka protocol executors are not built here; `findAllActiveByProtocol` simply excludes them today.
- `retryPolicy` and `rateLimitPolicy` profile fields remain unused; wiring them is a separate, later change if a concrete need arises.
- Direct `Customer` persistence + `GET /api/v1/customers` is a pre-existing gap the outbox-only approach here does not close.
