# Integraciones Design Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the navbar "Integration Profiles" page (and its "Flows" sibling tab) with the Claude Design reference (`Integration Console.dc.html`): rename to "Integraciones", add a real "última sync" column, and replace the boolean active/inactive status with a 6-value derived status model that includes a new persisted `paused` flag.

**Architecture:** Backend follows the existing hexagonal pattern of `IntegrationProfile` (domain → port → JPA adapter → service → controller), adding `paused` as a real persisted field and deriving `status` at the response layer from `active` + `paused` + the existing `SyncState` (never persisting `status` itself — same philosophy as `Flow.status()`). Frontend adds a small pure `timeAgo` pipe and updates the profile list/detail/dashboard/tabs components to surface the new fields.

**Tech Stack:** Spring Boot (Java), MySQL 8.4 + Flyway, Angular (standalone components, signals), NestJS BFF.

**Spec:** `docs/superpowers/specs/2026-08-30-integraciones-design-alignment.md`

## Global Constraints

- Navbar label: `'Integraciones'`, code: `'IX'` (was `'Integration Profiles'` / `'IP'`).
- Shared page `<h1>` on both the Integration Profiles and Flows tabs: `Integraciones`.
- Shared subtitle on both tabs: "Un perfil define cómo un dominio de negocio se acopla a una fuente externa. Un flujo compone varios pasos, ramas y destinos sobre esas mismas fuentes."
- Status derivation (never persisted, always computed): `active=false` → `INACTIVE`; `active=true, paused=true` → `PAUSED`; `active=true, paused=false`, no `SyncState` → `DRAFT`; `SyncState.lastRunStatus == FAILED` → `ERROR`; `SyncState.lastRunStatus == CANCELLED` → `DEGRADED`; `SyncState.lastRunStatus == SUCCESS` → `ACTIVE`.
- New `paused` column: `application/src/main/resources/db/migration/V15__add_integration_profile_paused.sql` (V14 is the last existing migration).
- Backend tests that touch persistence need MySQL running: `docker compose up -d mysql` before `mvn test`.
- Frontend tests use **Vitest**, not Jest (`vi.fn()`/`vi.spyOn()`/`vi.restoreAllMocks()`, globally available without import — confirmed by existing specs like `integration-profile-list.component.spec.ts`).
- Every existing call site of `IntegrationProfileView`'s and `IntegrationProfile.rehydrate`'s current constructor arities must keep compiling unchanged — add `paused` as a new overload/canonical field, never by editing the existing arities' parameter lists.

---

## File Structure

**Backend (`application/src/main/java/com/cl2/integration/`):**

- `domain/model/IntegrationProfileStatus.java` — new enum: `ACTIVE`, `PAUSED`, `DRAFT`, `ERROR`, `DEGRADED`, `INACTIVE`.
- `application/IntegrationProfileStatusResolver.java` — new pure resolver (keeps the `SyncRunStatus` dependency out of `domain/model`).
- `domain/model/IntegrationProfile.java` — **modify**: add `paused` field, `pause()`/`resume()`, a new 12-arg `rehydrate` overload.
- `application/IntegrationProfileView.java` — **modify**: add `paused` field via a new 12-arg canonical constructor; existing 10/11-arg constructors become defaulting overloads.
- `application/IntegrationProfileService.java` — **modify**: `toView` passes `paused`; add `pause(tenantId, profileId)` / `resume(tenantId, profileId)`.
- `adapter/out/persistence/IntegrationProfileJpaEntity.java` — **modify**: add `paused` column, use the new `rehydrate` overload.
- `adapter/out/persistence/SpringDataIntegrationProfileRepository.java` — **modify**: `updateIfVersionMatches` gains a `paused` param/column.
- `adapter/out/persistence/IntegrationProfilePersistenceAdapter.java` — **modify**: pass `profile.paused()` through.
- `adapter/in/web/dto/IntegrationProfileResponse.java` — **modify**: add `paused`, `status`, `lastSyncAt`; new `from(view, syncState, objectMapper)` overload.
- `adapter/in/web/IntegrationProfileController.java` — **modify**: inject `SyncStateRepository`, add `POST /{profileId}/pause` and `/resume`, route every response through a shared `toResponse` helper.
- `src/main/resources/db/migration/V15__add_integration_profile_paused.sql` — new migration.

**Backend tests:**

- `domain/model/IntegrationProfileTest.java` — **modify**: add `pause()`/`resume()` cases.
- `application/IntegrationProfileStatusResolverTest.java` — new.
- `application/IntegrationProfileServiceTest.java` — **modify**: add `pause`/`resume` cases (uses the existing `FakeIntegrationProfileRepository` already in that file).
- `adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java` — **modify**: add a `paused` round-trip case.
- `adapter/in/web/IntegrationProfileControllerTest.java` — **modify**: add `pause`/`resume` endpoint cases, update the `@MockitoBean`s to include `SyncStateRepository`.

**BFF (`backoffice/apps/bff/src/gateway-proxy/`):**

- `gateway-proxy.service.ts` — **modify**: add `pauseIntegrationProfile`, `resumeIntegrationProfile`.
- `gateway-proxy.controller.ts` — **modify**: add matching routes.

**Frontend (`backoffice/apps/integration-mfe/src/app/`):**

- `shared/time-ago.pipe.ts` — new pure pipe for relative-time formatting.
- `shared/time-ago.pipe.spec.ts` — new.
- `integration-profile/integration-profile.model.ts` — **modify**: add `IntegrationProfileStatus`, `paused`, `status`, `lastSyncAt`.
- `integration-profile/integration-profile.service.ts` — **modify**: add `pause()`/`resume()`.
- `integration-profile/integration-profile.service.spec.ts` — **modify**: add cases.
- `integration-profile/integration-profile-list.component.ts`/`.html`/`.spec.ts` — **modify**: shared `<h1>`, subtitle, "Última sync" column, 6-value status badges.
- `integration-profile/integration-profile-detail.component.ts`/`.html`/`.spec.ts` — **modify**: pause/resume button, status display.
- `flow/flow-list.component.html` — **modify**: shared `<h1>Integraciones</h1>` and subtitle.
- `shared/integration-tabs.component.ts`/`.spec.ts` — **modify**: Flows tab count badge.
- `dashboard/dashboard-page.component.ts`/`.html`/`.spec.ts` — **modify**: "requiere atención" includes `PAUSED`/`ERROR`/`DEGRADED`.
- `styles.css` — **modify**: add `.badge.error`, `.badge.degraded`, `.badge.paused`, `.badge.draft` classes.

**Shell (`backoffice/apps/shell/src/app/layout/`):**

- `sidebar.component.ts` — **modify**: navbar label/code.

---

### Task 1: `IntegrationProfileStatus` enum and resolver

**Files:**
- Create: `application/src/main/java/com/cl2/integration/domain/model/IntegrationProfileStatus.java`
- Create: `application/src/main/java/com/cl2/integration/application/IntegrationProfileStatusResolver.java`
- Test: `application/src/test/java/com/cl2/integration/application/IntegrationProfileStatusResolverTest.java`

**Interfaces:**
- Produces: `IntegrationProfileStatus` enum with values `ACTIVE, PAUSED, DRAFT, ERROR, DEGRADED, INACTIVE`. `IntegrationProfileStatusResolver.resolve(boolean active, boolean paused, SyncRunStatus lastRunStatus)` — static, `lastRunStatus` may be `null` (no `SyncState` yet).

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.application;

import com.cl2.integration.domain.model.IntegrationProfileStatus;
import com.cl2.integration.integration.sync.SyncRunStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationProfileStatusResolverTest {

    @Test
    void inactiveWinsOverEverythingElse() {
        assertThat(IntegrationProfileStatusResolver.resolve(false, true, SyncRunStatus.FAILED))
                .isEqualTo(IntegrationProfileStatus.INACTIVE);
    }

    @Test
    void pausedWinsOverSyncState() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, true, SyncRunStatus.FAILED))
                .isEqualTo(IntegrationProfileStatus.PAUSED);
    }

    @Test
    void draftWhenNoSyncStateExistsYet() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, false, null))
                .isEqualTo(IntegrationProfileStatus.DRAFT);
    }

    @Test
    void failedSyncMeansError() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, false, SyncRunStatus.FAILED))
                .isEqualTo(IntegrationProfileStatus.ERROR);
    }

    @Test
    void cancelledSyncMeansDegraded() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, false, SyncRunStatus.CANCELLED))
                .isEqualTo(IntegrationProfileStatus.DEGRADED);
    }

    @Test
    void successfulSyncMeansActive() {
        assertThat(IntegrationProfileStatusResolver.resolve(true, false, SyncRunStatus.SUCCESS))
                .isEqualTo(IntegrationProfileStatus.ACTIVE);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl application -am test -Dtest=IntegrationProfileStatusResolverTest`
Expected: FAIL to compile — `IntegrationProfileStatus`/`IntegrationProfileStatusResolver` do not exist yet.

- [ ] **Step 3: Create the enum**

```java
package com.cl2.integration.domain.model;

public enum IntegrationProfileStatus {
    ACTIVE,
    PAUSED,
    DRAFT,
    ERROR,
    DEGRADED,
    INACTIVE
}
```

- [ ] **Step 4: Create the resolver**

```java
package com.cl2.integration.application;

import com.cl2.integration.domain.model.IntegrationProfileStatus;
import com.cl2.integration.integration.sync.SyncRunStatus;

public final class IntegrationProfileStatusResolver {

    private IntegrationProfileStatusResolver() {
    }

    public static IntegrationProfileStatus resolve(boolean active, boolean paused, SyncRunStatus lastRunStatus) {
        if (!active) {
            return IntegrationProfileStatus.INACTIVE;
        }
        if (paused) {
            return IntegrationProfileStatus.PAUSED;
        }
        if (lastRunStatus == null) {
            return IntegrationProfileStatus.DRAFT;
        }
        return switch (lastRunStatus) {
            case FAILED -> IntegrationProfileStatus.ERROR;
            case CANCELLED -> IntegrationProfileStatus.DEGRADED;
            case SUCCESS -> IntegrationProfileStatus.ACTIVE;
        };
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl application -am test -Dtest=IntegrationProfileStatusResolverTest`
Expected: PASS (6 tests)

- [ ] **Step 6: Commit**

```bash
git add application/src/main/java/com/cl2/integration/domain/model/IntegrationProfileStatus.java application/src/main/java/com/cl2/integration/application/IntegrationProfileStatusResolver.java application/src/test/java/com/cl2/integration/application/IntegrationProfileStatusResolverTest.java
git commit -m "feat: add IntegrationProfileStatus and its resolver"
```

---

### Task 2: `paused` field on the domain model and persistence

**Files:**
- Create: `application/src/main/resources/db/migration/V15__add_integration_profile_paused.sql`
- Modify: `application/src/main/java/com/cl2/integration/domain/model/IntegrationProfile.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntity.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java`
- Modify: `application/src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java`
- Modify: `application/src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java`

**Interfaces:**
- Produces: `IntegrationProfile.paused()` (getter), `IntegrationProfile.pause()`, `IntegrationProfile.resume()` (both no-op if already in that state, otherwise bump `version`/`updatedAt` like `archive()`); `IntegrationProfile.rehydrate(UUID, UUID, String, String, SyncDirection, SourceOfTruth, IntegrationProfileConfiguration, boolean active, boolean paused, Instant, Instant, long)` — new 12-arg overload used by the JPA entity. The two existing `rehydrate`/`create` overloads keep their current signatures unchanged (they default `paused` to `false` internally).

- [ ] **Step 1: Write the migration**

```sql
ALTER TABLE integration_profile
    ADD COLUMN paused BOOLEAN NOT NULL DEFAULT FALSE;
```

Save as `application/src/main/resources/db/migration/V15__add_integration_profile_paused.sql`.

- [ ] **Step 2: Write the failing domain test**

Add to `application/src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java` (add these cases; the file already has other tests for `create`/`update`/`deactivate` — do not remove them):

```java
    @Test
    void pauseSetsPausedAndBumpsVersion() {
        IntegrationProfile profile = IntegrationProfile.create(UUID.randomUUID(), UUID.randomUUID(), "orders", "erp",
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM);

        IntegrationProfile paused = profile.pause();

        assertThat(paused.paused()).isTrue();
        assertThat(paused.version()).isEqualTo(1);
    }

    @Test
    void pauseIsANoOpWhenAlreadyPaused() {
        IntegrationProfile profile = IntegrationProfile.create(UUID.randomUUID(), UUID.randomUUID(), "orders", "erp",
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM).pause();

        IntegrationProfile pausedAgain = profile.pause();

        assertThat(pausedAgain.version()).isEqualTo(profile.version());
    }

    @Test
    void resumeClearsPausedAndBumpsVersion() {
        IntegrationProfile profile = IntegrationProfile.create(UUID.randomUUID(), UUID.randomUUID(), "orders", "erp",
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM).pause();

        IntegrationProfile resumed = profile.resume();

        assertThat(resumed.paused()).isFalse();
        assertThat(resumed.version()).isEqualTo(2);
    }

    @Test
    void resumeIsANoOpWhenNotPaused() {
        IntegrationProfile profile = IntegrationProfile.create(UUID.randomUUID(), UUID.randomUUID(), "orders", "erp",
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM);

        IntegrationProfile resumedAgain = profile.resume();

        assertThat(resumedAgain.version()).isEqualTo(profile.version());
    }

    @Test
    void newProfilesStartUnpaused() {
        IntegrationProfile profile = IntegrationProfile.create(UUID.randomUUID(), UUID.randomUUID(), "orders", "erp",
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM);

        assertThat(profile.paused()).isFalse();
    }
```

(Add the necessary `import static org.assertj.core.api.Assertions.assertThat;` and model imports if the file doesn't already have them — it does, since it already tests `create`/`deactivate`.)

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl application -am test -Dtest=IntegrationProfileTest`
Expected: FAIL to compile — `paused()`/`pause()`/`resume()` do not exist yet.

- [ ] **Step 4: Update the domain model**

In `IntegrationProfile.java`, add the field, update the private constructor's parameter list (insert `boolean paused` right after `boolean active`), and update every call to `new IntegrationProfile(...)` inside the class to pass the new field:

```java
    private final boolean active;
    private final boolean paused;
    private final Instant createdAt;
```

```java
    private IntegrationProfile(UUID id, UUID tenantId, String businessDomain, String externalSource,
                               SyncDirection direction, SourceOfTruth sourceOfTruth,
                               IntegrationProfileConfiguration configuration, boolean active, boolean paused,
                               Instant createdAt, Instant updatedAt, long version) {
        this.id = requireId(id, "id");
        this.tenantId = requireId(tenantId, "tenantId");
        this.businessDomain = requireNonBlank(businessDomain, "businessDomain");
        this.externalSource = requireNonBlank(externalSource, "externalSource");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.sourceOfTruth = Objects.requireNonNull(sourceOfTruth, "sourceOfTruth must not be null");
        this.configuration = configuration;
        this.active = active;
        this.paused = paused;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static IntegrationProfile create(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                            SyncDirection direction, SourceOfTruth sourceOfTruth) {
        return create(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, null);
    }

    public static IntegrationProfile create(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                            SyncDirection direction, SourceOfTruth sourceOfTruth,
                                            IntegrationProfileConfiguration configuration) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                configuration, true, false, now, now, 0);
    }

    public static IntegrationProfile rehydrate(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                                SyncDirection direction, SourceOfTruth sourceOfTruth, boolean active,
                                                Instant createdAt, Instant updatedAt, long version) {
        return rehydrate(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, null,
                active, createdAt, updatedAt, version);
    }

    public static IntegrationProfile rehydrate(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                                SyncDirection direction, SourceOfTruth sourceOfTruth,
                                                IntegrationProfileConfiguration configuration, boolean active,
                                                Instant createdAt, Instant updatedAt, long version) {
        return rehydrate(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, configuration,
                active, false, createdAt, updatedAt, version);
    }

    public static IntegrationProfile rehydrate(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                                SyncDirection direction, SourceOfTruth sourceOfTruth,
                                                IntegrationProfileConfiguration configuration, boolean active,
                                                boolean paused, Instant createdAt, Instant updatedAt, long version) {
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                configuration, active, paused, createdAt, updatedAt, version);
    }

    public IntegrationProfile update(String businessDomain, String externalSource, SyncDirection direction,
                                     SourceOfTruth sourceOfTruth, long expectedVersion) {
        return update(businessDomain, externalSource, direction, sourceOfTruth, this.configuration, expectedVersion);
    }

    public IntegrationProfile update(String businessDomain, String externalSource, SyncDirection direction,
                                     SourceOfTruth sourceOfTruth, IntegrationProfileConfiguration configuration,
                                     long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                configuration, active, paused, createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public IntegrationProfile deactivate() {
        if (!active) {
            return this;
        }
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                configuration, false, paused, createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public IntegrationProfile pause() {
        if (paused) {
            return this;
        }
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                configuration, active, true, createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public IntegrationProfile resume() {
        if (!paused) {
            return this;
        }
        return new IntegrationProfile(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                configuration, active, false, createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public boolean paused() {
        return paused;
    }
```

(Keep every other existing method — `id()`, `tenantId()`, `active()`, etc. — untouched.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl application -am test -Dtest=IntegrationProfileTest`
Expected: PASS (all cases, including the 5 new ones)

- [ ] **Step 6: Update the JPA entity**

In `IntegrationProfileJpaEntity.java`, add the column field (right after `active`):

```java
    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private boolean paused;
```

In the private constructor `IntegrationProfileJpaEntity(IntegrationProfile profile)`, right after `this.active = profile.active();`:

```java
        this.active = profile.active();
        this.paused = profile.paused();
```

In `toDomain()`, switch to the new 12-arg `rehydrate` overload:

```java
    IntegrationProfile toDomain() {
        IntegrationProfileConfiguration config = null;
        if (protocol != null || connector != null || adapter != null || endpoint != null
                || credentialRef != null || mappingJson != null || transformationJson != null
                || syncPolicyJson != null || retryPolicyJson != null || rateLimitPolicyJson != null
                || extractionConfigJson != null) {
            config = new IntegrationProfileConfiguration(
                    protocol, connector, adapter, endpoint, credentialRef,
                    mappingJson, transformationJson, syncPolicyJson, retryPolicyJson, rateLimitPolicyJson,
                    extractionConfigJson
            );
        }
        return IntegrationProfile.rehydrate(
                id, tenantId, businessDomain, externalSource, direction, sourceOfTruth,
                config, active, paused, createdAt, updatedAt, version);
    }
```

- [ ] **Step 7: Update the Spring Data repository's update query**

In `SpringDataIntegrationProfileRepository.java`, add `paused` to the `set` clause and the parameter list of `updateIfVersionMatches`:

```java
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntegrationProfileJpaEntity profile
            set profile.businessDomain = :businessDomain,
                profile.externalSource = :externalSource,
                profile.direction = :direction,
                profile.sourceOfTruth = :sourceOfTruth,
                profile.protocol = :protocol,
                profile.connector = :connector,
                profile.adapter = :adapter,
                profile.endpoint = :endpoint,
                profile.credentialRef = :credentialRef,
                profile.mappingJson = :mappingJson,
                profile.transformationJson = :transformationJson,
                profile.syncPolicyJson = :syncPolicyJson,
                profile.retryPolicyJson = :retryPolicyJson,
                profile.rateLimitPolicyJson = :rateLimitPolicyJson,
                profile.extractionConfigJson = :extractionConfigJson,
                profile.active = :active,
                profile.paused = :paused,
                profile.updatedAt = :updatedAt,
                profile.version = profile.version + 1
            where profile.tenantId = :tenantId
              and profile.id = :id
              and profile.version = :expectedVersion
            """)
    int updateIfVersionMatches(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("businessDomain") String businessDomain,
            @Param("externalSource") String externalSource,
            @Param("direction") com.cl2.integration.domain.model.SyncDirection direction,
            @Param("sourceOfTruth") com.cl2.integration.domain.model.SourceOfTruth sourceOfTruth,
            @Param("protocol") com.cl2.integration.domain.model.IntegrationProtocol protocol,
            @Param("connector") String connector,
            @Param("adapter") String adapter,
            @Param("endpoint") String endpoint,
            @Param("credentialRef") String credentialRef,
            @Param("mappingJson") String mappingJson,
            @Param("transformationJson") String transformationJson,
            @Param("syncPolicyJson") String syncPolicyJson,
            @Param("retryPolicyJson") String retryPolicyJson,
            @Param("rateLimitPolicyJson") String rateLimitPolicyJson,
            @Param("extractionConfigJson") String extractionConfigJson,
            @Param("active") boolean active,
            @Param("paused") boolean paused,
            @Param("updatedAt") java.time.Instant updatedAt);
```

- [ ] **Step 8: Update the persistence adapter's call site**

In `IntegrationProfilePersistenceAdapter.java`, add `profile.paused()` to the `updateIfVersionMatches` call, right after `profile.active(),`:

```java
                    profile.active(), profile.paused(), profile.updatedAt());
```

(The full call becomes: `..., config != null ? config.extractionConfig() : null, profile.active(), profile.paused(), profile.updatedAt());`)

- [ ] **Step 9: Write the failing persistence test**

Add to `IntegrationProfilePersistenceAdapterTest.java` (same style as its existing `updatesTheDraftWhenTheExpectedVersionMatches`-equivalent case — check the file for its exact existing test names for `save`/`update` and follow that pattern; add this new case regardless of exact neighboring names):

```java
    @Test
    void pausedFlagRoundTripsThroughSaveAndFindById() {
        IntegrationProfile profile = adapter.save(TENANT_ID, IntegrationProfile.create(UUID.randomUUID(), TENANT_ID,
                "orders", "erp", com.cl2.integration.domain.model.SyncDirection.INBOUND,
                com.cl2.integration.domain.model.SourceOfTruth.PLATFORM));

        IntegrationProfile paused = adapter.save(TENANT_ID, profile.pause());

        assertThat(paused.paused()).isTrue();
        IntegrationProfile reloaded = adapter.findById(TENANT_ID, profile.id());
        assertThat(reloaded.paused()).isTrue();
    }
```

(Use whatever `TENANT_ID` constant and `assertThat` import the existing test class already defines — read the file first to match its exact `@BeforeEach` cleanup and constant names before inserting.)

- [ ] **Step 10: Run the tests to verify they pass**

Run: `docker compose up -d mysql && mvn -pl application -am test -Dtest=IntegrationProfileTest,IntegrationProfilePersistenceAdapterTest`
Expected: PASS (all cases)

- [ ] **Step 11: Commit**

```bash
git add application/src/main/resources/db/migration/V15__add_integration_profile_paused.sql application/src/main/java/com/cl2/integration/domain/model/IntegrationProfile.java application/src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntity.java application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java application/src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java application/src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java application/src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java
git commit -m "feat: add persisted paused flag to IntegrationProfile"
```

---

### Task 3: Wire `paused`/`status`/`lastSyncAt` through service, response, and pause/resume endpoints

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/application/IntegrationProfileView.java`
- Modify: `application/src/main/java/com/cl2/integration/application/IntegrationProfileService.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileResponse.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java`
- Modify: `application/src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java`
- Modify: `application/src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java`

**Interfaces:**
- Consumes: `IntegrationProfile.paused()`/`.pause()`/`.resume()` (Task 2), `IntegrationProfileStatusResolver.resolve(...)` (Task 1), `SyncStateRepository.find(UUID)` returning `Optional<SyncState>` (existing, `com.cl2.integration.integration.sync.SyncStateRepository`).
- Produces: `IntegrationProfileView` gains a 12-arg canonical constructor `(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, configuration, active, paused, createdAt, updatedAt, version)` — the existing 10-arg (no configuration) and 11-arg (with configuration, no paused) constructors keep working, defaulting `paused=false`. `IntegrationProfileService.pause(UUID tenantId, UUID profileId)` / `.resume(...)` returning `IntegrationProfileView`. `IntegrationProfileResponse.from(IntegrationProfileView view, Optional<SyncState> syncState, ObjectMapper objectMapper)` returning a response with `paused: boolean`, `status: String`, `lastSyncAt: Instant`. `POST /api/v1/integration-profiles/{profileId}/pause` and `/resume`, both `200`.

- [ ] **Step 1: Add the `paused` field to `IntegrationProfileView`**

Replace the whole file with:

```java
package com.cl2.integration.application;

import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import java.time.Instant;
import java.util.UUID;

public record IntegrationProfileView(
        UUID id,
        UUID tenantId,
        String businessDomain,
        String externalSource,
        SyncDirection direction,
        SourceOfTruth sourceOfTruth,
        IntegrationProfileConfiguration configuration,
        boolean active,
        boolean paused,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public IntegrationProfileView(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                  SyncDirection direction, SourceOfTruth sourceOfTruth,
                                  boolean active, Instant createdAt, Instant updatedAt, long version) {
        this(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, null, active, false,
                createdAt, updatedAt, version);
    }

    public IntegrationProfileView(UUID id, UUID tenantId, String businessDomain, String externalSource,
                                  SyncDirection direction, SourceOfTruth sourceOfTruth,
                                  IntegrationProfileConfiguration configuration, boolean active,
                                  Instant createdAt, Instant updatedAt, long version) {
        this(id, tenantId, businessDomain, externalSource, direction, sourceOfTruth, configuration, active, false,
                createdAt, updatedAt, version);
    }
}
```

This keeps every existing caller (the 10-arg and 11-arg forms, used across `MappingDryRunServiceTest`,
`IntegrationProfileControllerTest`, `ProfileDeactivationHandlerTest`, `IntegrationProfileEventPublisherTest`)
compiling unchanged — they now default `paused` to `false`.

- [ ] **Step 2: Update `IntegrationProfileService`**

In `IntegrationProfileService.java`, update `toView` to pass `paused`:

```java
    private IntegrationProfileView toView(IntegrationProfile profile) {
        return new IntegrationProfileView(profile.id(), profile.tenantId(), profile.businessDomain(), profile.externalSource(),
                profile.direction(), profile.sourceOfTruth(), profile.configuration(), profile.active(), profile.paused(),
                profile.createdAt(), profile.updatedAt(), profile.version());
    }
```

Add two new public methods (place them right after `deactivate`):

```java
    @Transactional
    public IntegrationProfileView pause(UUID tenantId, UUID profileId) {
        IntegrationProfile profile = repository.findById(tenantId, profileId);
        IntegrationProfileView paused = toView(repository.save(tenantId, profile.pause()));
        publishEvent("IntegrationProfilePaused", paused);
        return paused;
    }

    @Transactional
    public IntegrationProfileView resume(UUID tenantId, UUID profileId) {
        IntegrationProfile profile = repository.findById(tenantId, profileId);
        IntegrationProfileView resumed = toView(repository.save(tenantId, profile.resume()));
        publishEvent("IntegrationProfileResumed", resumed);
        return resumed;
    }
```

- [ ] **Step 3: Write the failing service test**

Add to `IntegrationProfileServiceTest.java` (it already has a `FakeIntegrationProfileRepository` inner/nested class used by every other test — reuse it exactly as the existing tests do):

```java
    @Test
    void pausePersistsThePausedFlag() {
        IntegrationProfileView created = service.create(TENANT_ID, createCommand("orders", "erp"));

        IntegrationProfileView paused = service.pause(TENANT_ID, created.id());

        assertThat(paused.paused()).isTrue();
    }

    @Test
    void resumeClearsThePausedFlag() {
        IntegrationProfileView created = service.create(TENANT_ID, createCommand("orders", "erp"));
        service.pause(TENANT_ID, created.id());

        IntegrationProfileView resumed = service.resume(TENANT_ID, created.id());

        assertThat(resumed.paused()).isFalse();
    }

    @Test
    void pauseThrowsNotFoundForAnotherTenantsProfile() {
        IntegrationProfileView created = service.create(TENANT_ID, createCommand("orders", "erp"));

        assertThatThrownBy(() -> service.pause(OTHER_TENANT_ID, created.id()))
                .isInstanceOf(IntegrationProfileNotFoundException.class);
    }
```

(Use the file's existing `createCommand(String, String)` helper — it already exists since `createsAnActiveProfileForTheSuppliedTenant` calls it.)

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn -pl application -am test -Dtest=IntegrationProfileServiceTest`
Expected: FAIL to compile — `service.pause`/`service.resume` do not exist yet.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl application -am test -Dtest=IntegrationProfileServiceTest`
Expected: PASS (all cases, including the 3 new ones)

- [ ] **Step 6: Update `IntegrationProfileResponse`**

Replace the whole file with:

```java
package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.IntegrationProfileStatusResolver;
import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProfileStatus;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.sync.SyncState;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record IntegrationProfileResponse(
        UUID id,
        UUID tenantId,
        String businessDomain,
        String externalSource,
        SyncDirection syncDirection,
        SourceOfTruth sourceOfTruth,
        @JsonInclude(JsonInclude.Include.NON_NULL) ConfigurationResponse configuration,
        boolean active,
        boolean paused,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant lastSyncAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static IntegrationProfileResponse from(IntegrationProfileView view, Optional<SyncState> syncState,
                                                   ObjectMapper objectMapper) {
        ConfigurationResponse configResponse = ConfigurationResponse.from(view.configuration(), objectMapper);
        com.cl2.integration.integration.sync.SyncRunStatus lastRunStatus = syncState
                .map(SyncState::lastRunStatus).orElse(null);
        Instant lastSyncAt = syncState.map(SyncState::lastRunStartedAt).orElse(null);
        IntegrationProfileStatus status = IntegrationProfileStatusResolver.resolve(view.active(), view.paused(), lastRunStatus);
        return new IntegrationProfileResponse(view.id(), view.tenantId(), view.businessDomain(), view.externalSource(),
                view.direction(), view.sourceOfTruth(), configResponse, view.active(), view.paused(), status.name(),
                lastSyncAt, view.createdAt(), view.updatedAt(), view.version());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfigurationResponse(
            IntegrationProtocol protocol,
            String connector,
            String adapter,
            String endpoint,
            String credentialRef,
            JsonNode mapping,
            JsonNode transformation,
            JsonNode syncPolicy,
            JsonNode retryPolicy,
            JsonNode rateLimitPolicy,
            JsonNode extractionConfig
    ) {
        public static ConfigurationResponse from(IntegrationProfileConfiguration config, ObjectMapper objectMapper) {
            if (config == null) {
                return null;
            }
            return new ConfigurationResponse(
                    config.protocol(),
                    config.connector(),
                    config.adapter(),
                    config.endpoint(),
                    config.credentialRef(),
                    readTree(config.mapping(), objectMapper),
                    readTree(config.transformation(), objectMapper),
                    readTree(config.syncPolicy(), objectMapper),
                    readTree(config.retryPolicy(), objectMapper),
                    readTree(config.rateLimitPolicy(), objectMapper),
                    readTree(config.extractionConfig(), objectMapper)
            );
        }

        private static JsonNode readTree(String json, ObjectMapper objectMapper) {
            if (json == null) {
                return null;
            }
            try {
                return objectMapper.readTree(json);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
    }
}
```

Note: this removes the old `from(view, objectMapper)` two-arg factory and the unused `from(view)` one-arg
convenience factory (grep confirms neither is called anywhere outside this file and
`IntegrationProfileController`, which Step 8 updates in the same task).

- [ ] **Step 7: Add `SyncStateRepository` to `IntegrationProfileController` and route every response through it**

In `IntegrationProfileController.java`, add the import
`com.cl2.integration.integration.sync.SyncState` and
`com.cl2.integration.integration.sync.SyncStateRepository`, and `java.util.Optional`. Update the
constructor:

```java
    private final IntegrationProfileService service;
    private final IntegrationSyncService syncService;
    private final MappingDryRunService mappingDryRunService;
    private final SyncStateRepository syncStateRepository;
    private final ObjectMapper objectMapper;

    public IntegrationProfileController(
            IntegrationProfileService service,
            IntegrationSyncService syncService,
            MappingDryRunService mappingDryRunService,
            SyncStateRepository syncStateRepository,
            ObjectMapper objectMapper) {
        this.service = service;
        this.syncService = syncService;
        this.mappingDryRunService = mappingDryRunService;
        this.syncStateRepository = syncStateRepository;
        this.objectMapper = objectMapper;
    }
```

Replace every `IntegrationProfileResponse.from(x, objectMapper)` call with `toResponse(x)`, and add
`toResponse` plus the two new endpoints:

```java
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IntegrationProfileResponse create(@Valid @RequestBody CreateIntegrationProfileRequest request) {
        IntegrationProfileConfiguration configuration = request.configurationRequest().toDomain(objectMapper);
        return toResponse(service.create(TenantContext.requireTenantId(),
                new CreateIntegrationProfileCommand(request.businessDomain(), request.externalSource(), request.syncDirection(),
                        request.sourceOfTruth(), configuration)));
    }

    @GetMapping
    public List<IntegrationProfileResponse> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.list(TenantContext.requireTenantId(), activeOnly).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{profileId}")
    public IntegrationProfileResponse get(@PathVariable UUID profileId) {
        return toResponse(service.get(TenantContext.requireTenantId(), profileId));
    }

    @PutMapping("/{profileId}")
    public IntegrationProfileResponse update(
            @PathVariable UUID profileId,
            @Valid @RequestBody UpdateIntegrationProfileRequest request) {
        IntegrationProfileConfiguration configuration = request.configurationRequest().toDomain(objectMapper);
        return toResponse(service.update(TenantContext.requireTenantId(), profileId,
                new UpdateIntegrationProfileCommand(request.businessDomain(), request.externalSource(), request.syncDirection(),
                        request.sourceOfTruth(), configuration, request.expectedVersion())));
    }

    @PostMapping("/{profileId}/pause")
    public IntegrationProfileResponse pause(@PathVariable UUID profileId) {
        return toResponse(service.pause(TenantContext.requireTenantId(), profileId));
    }

    @PostMapping("/{profileId}/resume")
    public IntegrationProfileResponse resume(@PathVariable UUID profileId) {
        return toResponse(service.resume(TenantContext.requireTenantId(), profileId));
    }

    private IntegrationProfileResponse toResponse(com.cl2.integration.application.IntegrationProfileView view) {
        Optional<SyncState> syncState = syncStateRepository.find(view.id());
        return IntegrationProfileResponse.from(view, syncState, objectMapper);
    }
```

(Leave `triggerSync`, `mappingDryRun`, and `deactivate` exactly as they are — they don't return a
profile response.)

- [ ] **Step 8: Write the failing controller tests**

`IntegrationProfileControllerTest.java` already has `@MockitoBean private IntegrationProfileService service;`
and similar for the sync/mapping services — add one more:

```java
    @MockitoBean
    private com.cl2.integration.integration.sync.SyncStateRepository syncStateRepository;
```

And before every existing test that stubs `service.create`/`.get`/`.update`/`.list` and asserts on the
JSON body, add `given(syncStateRepository.find(org.mockito.ArgumentMatchers.any())).willReturn(java.util.Optional.empty());`
in a shared `@BeforeEach` (add one if the class doesn't have one yet):

```java
    @BeforeEach
    void stubNoSyncStateByDefault() {
        given(syncStateRepository.find(org.mockito.ArgumentMatchers.any())).willReturn(java.util.Optional.empty());
    }
```

(Import `org.junit.jupiter.api.BeforeEach` if not already imported.) Then add:

The file already has a private helper `profileView(UUID tenantId)` at the bottom (used by
`returns404WhenAnotherTenantsProfileIsRequested`-style tests) that builds an `IntegrationProfileView` with
`active=true` via the 11-arg (no-`paused`) constructor — reuse it for the happy-path cases below, and only
build a dedicated paused view where the test specifically needs `paused=true`:

```java
    @Test
    void pausesAProfile() throws Exception {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        IntegrationProfileView pausedView = new IntegrationProfileView(PROFILE_ID, TENANT_ID, "orders", "erp",
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM, null, true, true, now, now, 1);
        given(service.pause(TENANT_ID, PROFILE_ID)).willReturn(pausedView);

        mockMvc.perform(post(BASE_PATH + "/{profileId}/pause", PROFILE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void resumesAProfile() throws Exception {
        given(service.resume(TENANT_ID, PROFILE_ID)).willReturn(profileView(TENANT_ID));

        mockMvc.perform(post(BASE_PATH + "/{profileId}/resume", PROFILE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    void returnsErrorStatusWhenTheLastSyncFailed() throws Exception {
        given(service.get(TENANT_ID, PROFILE_ID)).willReturn(profileView(TENANT_ID));
        given(syncStateRepository.find(PROFILE_ID)).willReturn(java.util.Optional.of(
                new com.cl2.integration.integration.sync.SyncState(PROFILE_ID, null, java.time.Instant.parse("2026-08-30T00:00:00Z"),
                        com.cl2.integration.integration.sync.SyncRunStatus.FAILED, "boom")));

        mockMvc.perform(get(BASE_PATH + "/{profileId}", PROFILE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.lastSyncAt").value("2026-08-30T00:00:00Z"));
    }
```

If the existing helper at the bottom of the file is named differently from `profileView(UUID tenantId)`,
use its actual name instead — it is the method the file's existing `returns404When...`/tenant-isolation
tests already call to build a default `IntegrationProfileView`.

- [ ] **Step 9: Run the tests to verify they fail, then pass**

Run: `mvn -pl application -am test -Dtest=IntegrationProfileControllerTest`
Expected: first FAIL (missing `pause`/`resume` mappings and `SyncStateRepository` bean), then PASS after
Step 7's controller changes are in place.

- [ ] **Step 10: Run the full backend test suite**

Run: `docker compose up -d mysql redis kafka && mvn test`
Expected: BUILD SUCCESS

- [ ] **Step 11: Commit**

```bash
git add application/src/main/java/com/cl2/integration/application/IntegrationProfileView.java application/src/main/java/com/cl2/integration/application/IntegrationProfileService.java application/src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileResponse.java application/src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java application/src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java application/src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java
git commit -m "feat: expose paused/status/lastSyncAt and pause/resume endpoints"
```

---

### Task 4: BFF proxy routes for pause/resume

**Files:**
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts`
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts`

**Interfaces:**
- Produces: `GatewayProxyService.pauseIntegrationProfile(accessToken, profileId)`, `.resumeIntegrationProfile(accessToken, profileId)`; controller routes `POST integration-profiles/:profileId/pause` and `/resume`.

- [ ] **Step 1: Add the service methods**

In `gateway-proxy.service.ts`, immediately after `deactivateIntegrationProfile`:

```ts
  pauseIntegrationProfile(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/integration-profiles/${profileId}/pause`, accessToken, {});
  }

  resumeIntegrationProfile(accessToken: string, profileId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/integration-profiles/${profileId}/resume`, accessToken, {});
  }
```

- [ ] **Step 2: Add the controller routes**

In `gateway-proxy.controller.ts`, immediately after the existing `deactivateIntegrationProfile`/DELETE route:

```ts
  @Post('integration-profiles/:profileId/pause')
  pauseIntegrationProfile(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.pauseIntegrationProfile(request.session.tokens!.access_token!, profileId);
  }

  @Post('integration-profiles/:profileId/resume')
  resumeIntegrationProfile(@Req() request: AuthenticatedRequest, @Param('profileId') profileId: string) {
    return this.gatewayProxy.resumeIntegrationProfile(request.session.tokens!.access_token!, profileId);
  }
```

- [ ] **Step 3: Verify the BFF builds**

Run: `cd backoffice && npx nx build bff`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts
git commit -m "feat: proxy integration profile pause/resume through the BFF"
```

---

### Task 5: `timeAgo` pipe

**Files:**
- Create: `backoffice/apps/integration-mfe/src/app/shared/time-ago.pipe.ts`
- Create: `backoffice/apps/integration-mfe/src/app/shared/time-ago.pipe.spec.ts`

**Interfaces:**
- Produces: `TimeAgoPipe` (standalone, pipe name `timeAgo`), `transform(value: string | null): string` → `'—'` for `null`, `'hace N min'` / `'hace N h'` / `'hace N d'` otherwise.

- [ ] **Step 1: Write the failing test**

```ts
import { TimeAgoPipe } from './time-ago.pipe';

describe('TimeAgoPipe', () => {
  let pipe: TimeAgoPipe;

  beforeEach(() => {
    pipe = new TimeAgoPipe();
    vi.spyOn(Date, 'now').mockReturnValue(new Date('2026-08-30T12:00:00Z').getTime());
  });

  afterEach(() => vi.restoreAllMocks());

  it('returns an em dash for null', () => {
    expect(pipe.transform(null)).toBe('—');
  });

  it('formats minutes', () => {
    expect(pipe.transform('2026-08-30T11:55:00Z')).toBe('hace 5 min');
  });

  it('formats hours', () => {
    expect(pipe.transform('2026-08-30T09:00:00Z')).toBe('hace 3 h');
  });

  it('formats days', () => {
    expect(pipe.transform('2026-08-27T12:00:00Z')).toBe('hace 3 d');
  });

  it('treats sub-minute durations as just now', () => {
    expect(pipe.transform('2026-08-30T11:59:50Z')).toBe('hace un momento');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --test-file=time-ago.pipe.spec.ts`
Expected: FAIL — `./time-ago.pipe` module does not exist yet.

- [ ] **Step 3: Write the pipe**

```ts
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({ name: 'timeAgo', standalone: true })
export class TimeAgoPipe implements PipeTransform {
  transform(value: string | null): string {
    if (!value) return '—';
    const diffMs = Date.now() - new Date(value).getTime();
    const minutes = Math.floor(diffMs / 60000);
    if (minutes < 1) return 'hace un momento';
    if (minutes < 60) return `hace ${minutes} min`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `hace ${hours} h`;
    const days = Math.floor(hours / 24);
    return `hace ${days} d`;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --test-file=time-ago.pipe.spec.ts`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/shared/time-ago.pipe.ts backoffice/apps/integration-mfe/src/app/shared/time-ago.pipe.spec.ts
git commit -m "feat: add timeAgo pipe for relative-time display"
```

---

### Task 6: Frontend model and service

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.model.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.spec.ts`

**Interfaces:**
- Consumes: BFF routes from Task 4.
- Produces: `IntegrationProfileStatus` union type, `IntegrationProfile.paused: boolean`, `.status: IntegrationProfileStatus`, `.lastSyncAt: string | null`; `IntegrationProfileService.pause(id): Observable<IntegrationProfile>`, `.resume(id): Observable<IntegrationProfile>`.

- [ ] **Step 1: Update the model**

In `integration-profile.model.ts`, add the type and extend the interface:

```ts
export type IntegrationProfileStatus = 'ACTIVE' | 'PAUSED' | 'DRAFT' | 'ERROR' | 'DEGRADED' | 'INACTIVE';
```

```ts
export interface IntegrationProfile {
  id: string;
  tenantId: string;
  businessDomain: string;
  externalSource: string;
  syncDirection: SyncDirection;
  sourceOfTruth: SourceOfTruth;
  configuration: IntegrationProfileConfiguration | null;
  active: boolean;
  paused: boolean;
  status: IntegrationProfileStatus;
  lastSyncAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}
```

- [ ] **Step 2: Write the failing service test**

Add to `integration-profile.service.spec.ts`:

```ts
  it('pauses a profile', () => {
    service.pause('p-1').subscribe();
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1/pause');
    expect(request.request.method).toBe('POST');
    request.flush({});
  });

  it('resumes a profile', () => {
    service.resume('p-1').subscribe();
    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1/resume');
    expect(request.request.method).toBe('POST');
    request.flush({});
  });
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --test-file=integration-profile.service.spec.ts`
Expected: FAIL — `pause`/`resume` are not functions.

- [ ] **Step 4: Add the methods**

In `integration-profile.service.ts`:

```ts
  pause(id: string): Observable<IntegrationProfile> {
    return this.http.post<IntegrationProfile>(`${BASE_URL}/${id}/pause`, {});
  }

  resume(id: string): Observable<IntegrationProfile> {
    return this.http.post<IntegrationProfile>(`${BASE_URL}/${id}/resume`, {});
  }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --test-file=integration-profile.service.spec.ts`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.model.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.spec.ts
git commit -m "feat: add paused/status/lastSyncAt to IntegrationProfile and pause/resume to the service"
```

---

### Task 7: Global status badge styles

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/styles.css`

**Interfaces:**
- Produces: CSS classes `.badge.error`, `.badge.degraded`, `.badge.paused`, `.badge.draft` (in addition to the existing `.badge.active`/`.badge.inactive`).

- [ ] **Step 1: Add the classes**

In `styles.css`, right after the existing `.badge.inactive` rule (`.badge.inactive { color: var(--text-muted); background: var(--surface-2); }`):

```css
.badge.error { color: var(--err); background: color-mix(in oklab, var(--err) 15%, var(--surface)); }
.badge.degraded { color: var(--warn); background: color-mix(in oklab, var(--warn) 15%, var(--surface)); }
.badge.paused { color: var(--text-muted); background: var(--surface-2); }
.badge.draft { color: var(--text-dim); background: var(--surface-2); }
```

- [ ] **Step 2: Verify the app builds**

Run: `cd backoffice && npx nx build integration-mfe`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backoffice/apps/integration-mfe/src/styles.css
git commit -m "feat: add badge styles for the new profile status values"
```

---

### Task 8: Profile list — shared header, "Última sync" column, 6-value status

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.spec.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.html`

**Interfaces:**
- Consumes: `IntegrationProfile.status`/`.lastSyncAt` (Task 6), `TimeAgoPipe` (Task 5), `.badge.error`/`.badge.degraded`/`.badge.paused`/`.badge.draft` (Task 7).
- Produces: `IntegrationProfileListComponent.statusBadgeClass(status: IntegrationProfileStatus): string`, `.statusLabel(status: IntegrationProfileStatus): string`.

- [ ] **Step 1: Write the failing component test**

The file already has a `profile(overrides)` factory function (not a fixed object) used by every test —
extend its defaults to include the three new fields:

```ts
const profile = (overrides: Partial<Record<string, unknown>>) => ({
  id: 'p-1',
  tenantId: 't-1',
  businessDomain: 'orders',
  externalSource: 'erp',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: null,
  active: true,
  paused: false,
  status: 'ACTIVE',
  lastSyncAt: null,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  version: 2,
  ...overrides,
});
```

Then add:

```ts
  it('renders the shared Integraciones header and the Última sync column', () => {
    const fixture = TestBed.createComponent(IntegrationProfileListComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=true').flush([
      profile({ status: 'ERROR', lastSyncAt: null }),
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Integraciones');
    const statusCell = fixture.nativeElement.querySelector('.badge.error');
    expect(statusCell).toBeTruthy();
    expect(statusCell.textContent).toContain('Con error');
    const lastSyncCell = fixture.nativeElement.querySelectorAll('tbody td')[6];
    expect(lastSyncCell.textContent.trim()).toBe('—');
  });
```

(Adjust the `tbody td` index if the actual column order in the file differs from Task's expectation — the
column order is: Dominio/Fuente, Connector·Adapter, Dirección, Protocol, Source of truth, Endpoint, Última
sync, Estado — index `6` is "Última sync", 0-based.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --test-file=integration-profile-list.component.spec.ts`
Expected: FAIL — header still says "Integration Profiles", no `.badge.error` element exists yet.

- [ ] **Step 3: Update the component class**

In `integration-profile-list.component.ts`, import `IntegrationProfileStatus` and `TimeAgoPipe`:

```ts
import { IntegrationProfile, IntegrationProfileStatus, SyncDirection } from './integration-profile.model';
import { TimeAgoPipe } from '../shared/time-ago.pipe';
```

Add `TimeAgoPipe` to the component's `imports` array (alongside `IntegrationProfileWizardComponent`,
`IntegrationTabsComponent` from the earlier task). Replace the existing `statusBadgeClass` method:

```ts
  statusBadgeClass(status: IntegrationProfileStatus): string {
    return 'badge ' + status.toLowerCase();
  }

  statusLabel(status: IntegrationProfileStatus): string {
    const labels: Record<IntegrationProfileStatus, string> = {
      ACTIVE: 'Activo',
      PAUSED: 'Pausado',
      DRAFT: 'Borrador',
      ERROR: 'Con error',
      DEGRADED: 'Degradado',
      INACTIVE: 'Inactivo',
    };
    return labels[status];
  }
```

(Remove the old `statusBadgeClass(active: boolean)` implementation entirely — it's replaced by the one
above, which takes `IntegrationProfileStatus` instead of `boolean`.)

- [ ] **Step 4: Update the template**

In `integration-profile-list.component.html`, change the `<h1>` and subtitle:

```html
      <h1 id="integration-profiles-heading">Integraciones</h1>
      <p>Un perfil define cómo un dominio de negocio se acopla a una fuente externa. Un flujo compone varios pasos, ramas y destinos sobre esas mismas fuentes.</p>
```

Change the table header from "Actualizado" to "Última sync":

```html
            <th scope="col">Última sync</th>
```

Change the row's data cell (was `{{ profile.updatedAt.slice(0, 10) }}`) and the status cell:

```html
              <td>{{ profile.lastSyncAt | timeAgo }}</td>
              <td><span [class]="statusBadgeClass(profile.status)">{{ statusLabel(profile.status) }}</span></td>
```

(These replace the existing `<td>{{ profile.updatedAt.slice(0, 10) }}</td>` and
`<td><span [class]="statusBadgeClass(profile.active)">{{ profile.active ? 'Activo' : 'Inactivo' }}</span></td>`
lines — keep every other `<td>` in that row unchanged.)

- [ ] **Step 5: Update `flow-list.component.html`**

Change its `<h1>` and subtitle to match (the design uses the identical header/subtitle on both tabs):

```html
      <h1 id="flows-heading">Integraciones</h1>
      <p>Un perfil define cómo un dominio de negocio se acopla a una fuente externa. Un flujo compone varios pasos, ramas y destinos sobre esas mismas fuentes.</p>
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --test-file=integration-profile-list.component.spec.ts,flow-list.component.spec.ts`
Expected: PASS (fix any other pre-existing assertion in either spec file that checked for the old header
text — e.g. if `flow-list.component.spec.ts` asserts `'Flows'` as the `<h1>` text anywhere, update that
assertion to `'Integraciones'`)

- [ ] **Step 7: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.spec.ts backoffice/apps/integration-mfe/src/app/flow/flow-list.component.html
git commit -m "feat: align profile list header and status/last-sync columns with the design"
```

---

### Task 9: Profile detail — pause/resume button

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts`

**Interfaces:**
- Consumes: `IntegrationProfileService.pause`/`.resume` (Task 6).
- Produces: `IntegrationProfileDetailComponent.togglePause(): void`.

- [ ] **Step 1: Write the failing test**

The file has a fixed fixture object named `FULL_PROFILE` (not a factory) — add the three new fields to it:

```ts
const FULL_PROFILE = {
  id: 'p-1',
  tenantId: 't-1',
  businessDomain: 'vehicle',
  externalSource: 'SIGO',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: null,
  active: true,
  paused: false,
  status: 'ACTIVE',
  lastSyncAt: null,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  version: 7,
};
```

Then add (the file already loads the detail via `http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE)` in its other tests — reuse that exact URL):

```ts
  it('pauses an active profile', () => {
    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles/p-1').flush(FULL_PROFILE);
    fixture.detectChanges();

    const pauseBtn = fixture.nativeElement.querySelector('[data-testid="pause-profile"]');
    pauseBtn.click();

    const request = http.expectOne('/bff/api/v1/integration-profiles/p-1/pause');
    expect(request.request.method).toBe('POST');
    request.flush({ ...FULL_PROFILE, paused: true, status: 'PAUSED' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="pause-profile"]').textContent.trim()).toBe('Reanudar');
  });
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --test-file=integration-profile-detail.component.spec.ts`
Expected: FAIL — no `[data-testid="pause-profile"]` element exists yet.

- [ ] **Step 3: Add `togglePause` to the component**

In `integration-profile-detail.component.ts`, add a method right after `deactivateProfile`:

```ts
  togglePause(): void {
    const current = this.profile();
    if (!current) return;
    const action = current.paused ? this.profileService.resume(current.id) : this.profileService.pause(current.id);
    action.subscribe({
      next: (updated) => {
        this.profile.set(updated);
        this.toast.show(updated.paused ? 'Perfil pausado.' : 'Perfil reanudado.');
      },
      error: () => this.toast.show('No se pudo cambiar el estado del perfil.'),
    });
  }
```

- [ ] **Step 4: Add the button to the template**

In `integration-profile-detail.component.html`, add the button inside `.detail-actions`, right before the
existing `@if (p.active) { ... deactivate ... }` block:

```html
        <div class="detail-actions">
          @if (p.active) {
            <button type="button" data-testid="pause-profile" class="btn" (click)="togglePause()">{{ p.paused ? 'Reanudar' : 'Pausar' }}</button>
            <button type="button" data-testid="deactivate-profile" class="btn" (click)="deactivateProfile()">Desactivar perfil</button>
          } @else {
            <span class="inactive-note">Perfil inactivo — no hay acción de reactivación disponible en la consola.</span>
          }
          <button type="button" data-testid="save-profile" class="btn btn-primary" [disabled]="saving()" (click)="save()">Guardar cambios</button>
        </div>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --test-file=integration-profile-detail.component.spec.ts`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.ts backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.html backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-detail.component.spec.ts
git commit -m "feat: add pause/resume button to the profile detail page"
```

---

### Task 10: Navbar rename and Flows tab count badge

**Files:**
- Modify: `backoffice/apps/shell/src/app/layout/sidebar.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/shared/integration-tabs.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/shared/integration-tabs.component.spec.ts`

**Interfaces:**
- Consumes: `FlowService.list()` (existing, `backoffice/apps/integration-mfe/src/app/flow/flow.service.ts`).
- Produces: `IntegrationTabsComponent.flowCount: Signal<number | null>`.

- [ ] **Step 1: Rename the navbar entry**

In `sidebar.component.ts`, change:

```ts
    { path: '/integration/profiles', label: 'Integraciones', code: 'IX' },
```

(was `{ path: '/integration/profiles', label: 'Integration Profiles', code: 'IP' }`).

- [ ] **Step 2: Write the failing tabs test**

Replace the whole `integration-tabs.component.spec.ts` with:

```ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { IntegrationTabsComponent } from './integration-tabs.component';

describe('IntegrationTabsComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [IntegrationTabsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders navigation tabs', () => {
    const fixture = TestBed.createComponent(IntegrationTabsComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Integration Profiles');
    expect(fixture.nativeElement.textContent).toContain('Flows');
  });

  it('shows the flow count badge', () => {
    const fixture = TestBed.createComponent(IntegrationTabsComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows').flush([{ id: 'f-1' }, { id: 'f-2' }]);
    fixture.detectChanges();

    const badge = fixture.nativeElement.querySelector('.tab-badge');
    expect(badge.textContent.trim()).toBe('2');
  });

  it('hides the badge if the flow count request fails', () => {
    const fixture = TestBed.createComponent(IntegrationTabsComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows').flush('error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.tab-badge')).toBeFalsy();
  });
});
```

- [ ] **Step 2b: Run the test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --test-file=integration-tabs.component.spec.ts`
Expected: FAIL — the component doesn't call `FlowService` yet, so `http.expectOne('/bff/api/v1/flows')`
finds no matching request.

- [ ] **Step 3: Update the component**

Replace `integration-tabs.component.ts` with:

```ts
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { FlowService } from '../flow/flow.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-integration-tabs',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="integration-tabs" aria-label="Secciones de Integraciones">
      <a routerLink="/integration/profiles" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">
        Integration Profiles
      </a>
      <a routerLink="/integration/flows" routerLinkActive="active">
        Flows
        @if (flowCount(); as count) {
          <span class="tab-badge">{{ count }}</span>
        }
      </a>
    </nav>
  `,
  styles: [
    `
      .integration-tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--border); }
      .integration-tabs a {
        display: flex;
        align-items: center;
        gap: 7px;
        text-decoration: none;
        color: var(--text-muted);
        padding: 9px 14px;
        font-weight: 500;
        border-bottom: 2px solid transparent;
      }
      .integration-tabs a:hover { color: var(--text); }
      .integration-tabs a.active { color: var(--text); font-weight: 600; border-bottom-color: var(--accent); }
      .tab-badge {
        font-family: 'IBM Plex Mono', monospace;
        font-size: 10px;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 3px;
        padding: 1px 5px;
      }
    `,
  ],
})
export class IntegrationTabsComponent implements OnInit {
  private readonly flowService = inject(FlowService);

  readonly flowCount = signal<number | null>(null);

  ngOnInit(): void {
    this.flowService.list().subscribe({
      next: (flows) => this.flowCount.set(flows.length),
      error: () => this.flowCount.set(null),
    });
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --test-file=integration-tabs.component.spec.ts`
Expected: PASS (3 tests)

- [ ] **Step 5: Run the shell's tests**

Run: `cd backoffice && npx nx test shell`
Expected: PASS (confirms the `sidebar.component` label change didn't break any shell spec asserting the
old label text — fix any such assertion to `'Integraciones'` if one exists)

- [ ] **Step 6: Commit**

```bash
git add backoffice/apps/shell/src/app/layout/sidebar.component.ts backoffice/apps/integration-mfe/src/app/shared/integration-tabs.component.ts backoffice/apps/integration-mfe/src/app/shared/integration-tabs.component.spec.ts
git commit -m "feat: rename navbar entry to Integraciones and add Flows tab count badge"
```

---

### Task 11: Dashboard attention list includes paused/error/degraded profiles

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.spec.ts`

**Interfaces:**
- Consumes: `IntegrationProfile.status` (Task 6).
- Produces: `DashboardPageComponent.attention: Signal<IntegrationProfile[]>` now sourced from status instead of `!active`; `.attentionIssueLabel(status): string`, `.attentionBadgeClass(status): string`, `.attentionStatusLabel(status): string`.

- [ ] **Step 1: Write the failing test**

In `dashboard-page.component.spec.ts`, update the `profile()` helper to include the new required fields:

```ts
const profile = (overrides: Partial<Record<string, unknown>>) => ({
  id: 'id',
  tenantId: 't-1',
  businessDomain: 'vehicle',
  externalSource: 'SIGO',
  syncDirection: 'INBOUND',
  sourceOfTruth: 'EXTERNAL',
  configuration: null,
  active: true,
  paused: false,
  status: 'ACTIVE',
  lastSyncAt: null,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  version: 1,
  ...overrides,
});
```

Update the existing `'computes real KPI counts...'` test's third profile to also set `status: 'INACTIVE'`
(it already sets `active: false`; keep that, just add `status: 'INACTIVE'` to the same `profile({...})`
call so the new attention filter still picks it up). Then add:

```ts
  it('includes paused and error profiles in the attention list even when active', () => {
    const fixture = TestBed.createComponent(DashboardPageComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/integration-profiles?activeOnly=false').flush([
      profile({ id: 'p1', active: true, status: 'PAUSED', businessDomain: 'vehicle-model', externalSource: 'SIGO' }),
      profile({ id: 'p2', active: true, status: 'ERROR', businessDomain: 'customer', externalSource: 'SAP' }),
      profile({ id: 'p3', active: true, status: 'DRAFT', businessDomain: 'waybill', externalSource: 'TMS' }),
    ]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('vehicle-model');
    expect(text).toContain('customer');
    expect(text).not.toContain('waybill');
  });
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --test-file=dashboard-page.component.spec.ts`
Expected: FAIL — `attention` still filters on `!p.active`, so `PAUSED`/`ERROR` (both `active: true`) never
appear.

- [ ] **Step 3: Update the component**

In `dashboard-page.component.ts`, keep `inactiveProfiles`/`inactiveCount` exactly as they are (still
`active`-based, feeding the "PERFILES INACTIVOS" KPI), and change only `attention`:

```ts
  private static readonly ATTENTION_STATUSES: IntegrationProfileStatus[] = ['PAUSED', 'ERROR', 'DEGRADED'];

  readonly attention = computed(() =>
    this.profiles().filter((p) => DashboardPageComponent.ATTENTION_STATUSES.includes(p.status)).slice(0, 3),
  );

  attentionIssueLabel(status: IntegrationProfileStatus): string {
    const labels: Partial<Record<IntegrationProfileStatus, string>> = {
      PAUSED: 'Pausado por el operador',
      ERROR: 'Última sincronización con error',
      DEGRADED: 'Última sincronización interrumpida',
    };
    return labels[status] ?? '';
  }

  attentionBadgeClass(status: IntegrationProfileStatus): string {
    return 'badge ' + status.toLowerCase();
  }

  attentionStatusLabel(status: IntegrationProfileStatus): string {
    const labels: Partial<Record<IntegrationProfileStatus, string>> = {
      PAUSED: 'Pausado',
      ERROR: 'Con error',
      DEGRADED: 'Degradado',
    };
    return labels[status] ?? status;
  }
```

Add the import: `import { IntegrationProfile, IntegrationProfileStatus, SourceOfTruth, SyncDirection } from '../integration-profile/integration-profile.model';` (extends the existing import line with `IntegrationProfileStatus`).

- [ ] **Step 4: Update the template**

In `dashboard-page.component.html`, replace the attention row's issue/badge markup:

```html
              <span class="attention-issue">{{ attentionIssueLabel(p.status) }}</span>
              <span [class]="attentionBadgeClass(p.status)">{{ attentionStatusLabel(p.status) }}</span>
```

(These replace the existing `<span class="attention-issue">Perfil inactivo</span>` and
`<span class="badge inactive">Inactivo</span>` lines inside the `@for (p of attention(); track p.id)` loop.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --test-file=dashboard-page.component.spec.ts`
Expected: PASS (all cases, including the updated KPI-count test and the new attention-list test)

- [ ] **Step 6: Run the full frontend test suite and build**

Run: `cd backoffice && npx nx test integration-mfe && npx nx test shell && npx nx test bff && npx nx build integration-mfe && npx nx build shell && npx nx build bff`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 7: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.ts backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.html backoffice/apps/integration-mfe/src/app/dashboard/dashboard-page.component.spec.ts
git commit -m "feat: include paused/error/degraded profiles in the attention list"
```

---

## Manual Verification

1. `docker compose up -d --build mysql redis kafka app middleware`.
2. Serve the shell locally per `backoffice/README.md`.
3. Log in with `superset/superset`, confirm the navbar item now reads "Integraciones".
4. Open `/integration/profiles`: confirm the `<h1>Integraciones</h1>`, the two-sentence subtitle, the
   "Última sync" column (shows "—" for every seeded profile until a real sync runs), and that at least one
   profile's status badge renders correctly for whichever states exist in the seeded data.
5. Open `/integration/flows`: confirm the same shared `<h1>Integraciones</h1>` and subtitle, and that the
   "Flows" tab badge shows the real flow count.
6. On a profile's detail page, click "Pausar" and confirm the button flips to "Reanudar" and the list's
   status badge updates to "Pausado" on next visit.
7. On the dashboard, confirm a paused or errored profile (if any exist) now shows up under "Perfiles que
   requieren atención" with the correct label.
