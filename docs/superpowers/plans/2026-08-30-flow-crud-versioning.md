# Flow CRUD and Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first slice of the Flows backend — create/list/edit a
flow's draft graph, publish it as an immutable versioned snapshot, and roll
back to a previous version — plus the BFF proxy routes and backoffice UI
needed to replace the current "no disponible" placeholders.

**Architecture:** Hexagonal, mirroring `IntegrationProfile` exactly: an
immutable `Flow` domain aggregate with optimistic locking, a `FlowRepository`
port, a JPA persistence adapter, an application `FlowService`, and a REST
`FlowController`. A second aggregate, `FlowVersion`, is an immutable snapshot
created on publish. The BFF (`backoffice/apps/bff`) proxies the new
`/api/v1/flows*` endpoints the same way it proxies integration-profiles. The
Angular backoffice (`backoffice/apps/integration-mfe`) gets a real
`flow.service.ts` and a functional (non-canvas) designer form.

**Tech Stack:** Java 21 / Spring Boot (Spring Data JPA, Flyway, MySQL) for
`application`; NestJS for `backoffice/apps/bff`; Angular 22 standalone
components for `backoffice/apps/integration-mfe`.

**Spec:** `docs/superpowers/specs/2026-08-30-flow-crud-versioning-design.md`

## Global Constraints

- Tenant isolation: every operation uses `TenantContext.requireTenantId()`;
  no request payload may set or override the tenant.
- `flow:publisher` role enforcement is explicitly out of scope for this
  slice — publish/rollback are open to any authenticated user of the tenant.
- Execution metrics (`execCount24h`, `errorRate`, `p95`, executions, DLQ) are
  out of scope — not part of the `Flow`/`FlowVersion` model in this slice.
- The graph (`draftGraph` / `FlowVersion.graph`) is stored and returned as
  opaque JSON; the backend does not validate node/edge structure.
- Optimistic locking on `Flow.version` follows the exact
  `updateIfVersionMatches` pattern used by `IntegrationProfile` — conflicts
  throw `FlowConflictException` → HTTP 409.
- All new Java files follow the existing package layout under
  `application/src/main/java/com/cl2/integration/{domain,application,adapter}`.

---

## Task 1: `Flow` domain model

**Files:**
- Create: `application/src/main/java/com/cl2/integration/domain/model/FlowStatus.java`
- Create: `application/src/main/java/com/cl2/integration/domain/model/Flow.java`
- Create: `application/src/main/java/com/cl2/integration/application/exception/FlowConflictException.java`
- Test: `application/src/test/java/com/cl2/integration/domain/model/FlowTest.java`

**Interfaces:**
- Produces: `Flow.create(UUID id, UUID tenantId, String code, String name)`,
  `Flow.rehydrate(UUID id, UUID tenantId, String code, String name, String draftGraph, String triggerSummary, Integer activeVersionNumber, boolean archived, Instant createdAt, Instant updatedAt, long version)`,
  `flow.updateDraft(String name, String triggerSummary, String draftGraph, long expectedVersion)`,
  `flow.withActiveVersion(int versionNumber)`, `flow.archive()`,
  `flow.status()` returning `FlowStatus` (`DRAFT`, `PUBLISHED`, `OBSOLETE`),
  accessors `id()`, `tenantId()`, `code()`, `name()`, `draftGraph()`,
  `triggerSummary()`, `activeVersionNumber()`, `archived()`, `createdAt()`,
  `updatedAt()`, `version()`.

- [ ] **Step 1: Write the failing test**

Create `application/src/test/java/com/cl2/integration/domain/model/FlowTest.java`:

```java
package com.cl2.integration.domain.model;

import com.cl2.integration.application.exception.FlowConflictException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowTest {

    private static final UUID FLOW_ID = UUID.fromString("3c32c264-9163-4985-a3df-cb67a1031039");
    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

    @Test
    void createBuildsADraftFlowAtVersionZero() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/vehiculo-alta", "Alta de vehiculos");

        assertThat(flow.id()).isEqualTo(FLOW_ID);
        assertThat(flow.tenantId()).isEqualTo(TENANT_ID);
        assertThat(flow.code()).isEqualTo("flow/vehiculo-alta");
        assertThat(flow.name()).isEqualTo("Alta de vehiculos");
        assertThat(flow.draftGraph()).isNull();
        assertThat(flow.activeVersionNumber()).isNull();
        assertThat(flow.archived()).isFalse();
        assertThat(flow.version()).isZero();
        assertThat(flow.status()).isEqualTo(FlowStatus.DRAFT);
    }

    @Test
    void rejectsBlankCode() {
        assertThatThrownBy(() -> Flow.create(FLOW_ID, TENANT_ID, "  ", "name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateDraftIncrementsVersionAndKeepsStatusDraft() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");

        Flow updated = flow.updateDraft("X renamed", "CRON */5", "{\"nodes\":[]}", 0);

        assertThat(updated.name()).isEqualTo("X renamed");
        assertThat(updated.triggerSummary()).isEqualTo("CRON */5");
        assertThat(updated.draftGraph()).isEqualTo("{\"nodes\":[]}");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.status()).isEqualTo(FlowStatus.DRAFT);
    }

    @Test
    void updateDraftRejectsAStaleExpectedVersion() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");

        assertThatThrownBy(() -> flow.updateDraft("X", null, null, 5))
                .isInstanceOf(FlowConflictException.class);
    }

    @Test
    void withActiveVersionMovesStatusToPublished() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");

        Flow published = flow.withActiveVersion(1);

        assertThat(published.activeVersionNumber()).isEqualTo(1);
        assertThat(published.status()).isEqualTo(FlowStatus.PUBLISHED);
        assertThat(published.version()).isEqualTo(1);
    }

    @Test
    void archiveMovesStatusToObsoleteRegardlessOfActiveVersion() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X").withActiveVersion(1);

        Flow archived = flow.archive();

        assertThat(archived.archived()).isTrue();
        assertThat(archived.status()).isEqualTo(FlowStatus.OBSOLETE);
    }

    @Test
    void archiveIsIdempotent() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X").archive();

        Flow archivedAgain = flow.archive();

        assertThat(archivedAgain.version()).isEqualTo(flow.version());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd application && mvn -q -Dtest=FlowTest test`
Expected: FAIL — compilation error, `Flow`/`FlowStatus`/`FlowConflictException` do not exist yet.

- [ ] **Step 3: Write the minimal implementation**

Create `application/src/main/java/com/cl2/integration/domain/model/FlowStatus.java`:

```java
package com.cl2.integration.domain.model;

public enum FlowStatus {
    DRAFT,
    PUBLISHED,
    OBSOLETE
}
```

Create `application/src/main/java/com/cl2/integration/application/exception/FlowConflictException.java`:

```java
package com.cl2.integration.application.exception;

public class FlowConflictException extends RuntimeException {

    public FlowConflictException(String message) {
        super(message);
    }
}
```

Create `application/src/main/java/com/cl2/integration/domain/model/Flow.java`:

```java
package com.cl2.integration.domain.model;

import com.cl2.integration.application.exception.FlowConflictException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class Flow {

    private final UUID id;
    private final UUID tenantId;
    private final String code;
    private final String name;
    private final String draftGraph;
    private final String triggerSummary;
    private final Integer activeVersionNumber;
    private final boolean archived;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private Flow(UUID id, UUID tenantId, String code, String name, String draftGraph, String triggerSummary,
                 Integer activeVersionNumber, boolean archived, Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.code = requireNonBlank(code, "code");
        this.name = requireNonBlank(name, "name");
        this.draftGraph = draftGraph;
        this.triggerSummary = triggerSummary;
        this.activeVersionNumber = activeVersionNumber;
        this.archived = archived;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Flow create(UUID id, UUID tenantId, String code, String name) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new Flow(id, tenantId, code, name, null, null, null, false, now, now, 0);
    }

    public static Flow rehydrate(UUID id, UUID tenantId, String code, String name, String draftGraph,
                                  String triggerSummary, Integer activeVersionNumber, boolean archived,
                                  Instant createdAt, Instant updatedAt, long version) {
        return new Flow(id, tenantId, code, name, draftGraph, triggerSummary, activeVersionNumber, archived,
                createdAt, updatedAt, version);
    }

    public Flow updateDraft(String name, String triggerSummary, String draftGraph, long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        return new Flow(id, tenantId, code, name, draftGraph, triggerSummary, activeVersionNumber, archived,
                createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public Flow withActiveVersion(int versionNumber) {
        return new Flow(id, tenantId, code, name, draftGraph, triggerSummary, versionNumber, archived,
                createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public Flow archive() {
        if (archived) {
            return this;
        }
        return new Flow(id, tenantId, code, name, draftGraph, triggerSummary, activeVersionNumber, true,
                createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), version + 1);
    }

    public FlowStatus status() {
        if (archived) {
            return FlowStatus.OBSOLETE;
        }
        return activeVersionNumber == null ? FlowStatus.DRAFT : FlowStatus.PUBLISHED;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String draftGraph() {
        return draftGraph;
    }

    public String triggerSummary() {
        return triggerSummary;
    }

    public Integer activeVersionNumber() {
        return activeVersionNumber;
    }

    public boolean archived() {
        return archived;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new FlowConflictException("Flow version does not match expected version");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd application && mvn -q -Dtest=FlowTest test`
Expected: PASS — 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/domain/model/Flow.java \
        application/src/main/java/com/cl2/integration/domain/model/FlowStatus.java \
        application/src/main/java/com/cl2/integration/application/exception/FlowConflictException.java \
        application/src/test/java/com/cl2/integration/domain/model/FlowTest.java
git commit -m "feat: add Flow domain model with draft/publish/archive lifecycle"
```

---

## Task 2: `FlowVersion` domain model

**Files:**
- Create: `application/src/main/java/com/cl2/integration/domain/model/FlowVersionState.java`
- Create: `application/src/main/java/com/cl2/integration/domain/model/FlowVersion.java`
- Create: `application/src/main/java/com/cl2/integration/application/exception/FlowNotFoundException.java`
- Test: `application/src/test/java/com/cl2/integration/domain/model/FlowVersionTest.java`

**Interfaces:**
- Consumes: nothing from Task 1 directly (independent aggregate).
- Produces: `FlowVersion.publish(UUID id, UUID flowId, UUID tenantId, int versionNumber, String graph, String publishedBy)`
  (state `ACTIVE`), `FlowVersion.rehydrate(UUID id, UUID flowId, UUID tenantId, int versionNumber, String graph, FlowVersionState state, String publishedBy, Instant publishedAt)`,
  `flowVersion.withState(FlowVersionState newState)`, accessors `id()`,
  `flowId()`, `tenantId()`, `versionNumber()`, `graph()`, `state()`,
  `publishedBy()`, `publishedAt()`.

- [ ] **Step 1: Write the failing test**

Create `application/src/test/java/com/cl2/integration/domain/model/FlowVersionTest.java`:

```java
package com.cl2.integration.domain.model;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowVersionTest {

    private static final UUID VERSION_ID = UUID.fromString("3c32c264-9163-4985-a3df-cb67a1031039");
    private static final UUID FLOW_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");
    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

    @Test
    void publishCreatesAnActiveVersion() {
        FlowVersion version = FlowVersion.publish(VERSION_ID, FLOW_ID, TENANT_ID, 1, "{\"nodes\":[]}", "user@tenant");

        assertThat(version.id()).isEqualTo(VERSION_ID);
        assertThat(version.flowId()).isEqualTo(FLOW_ID);
        assertThat(version.tenantId()).isEqualTo(TENANT_ID);
        assertThat(version.versionNumber()).isEqualTo(1);
        assertThat(version.graph()).isEqualTo("{\"nodes\":[]}");
        assertThat(version.state()).isEqualTo(FlowVersionState.ACTIVE);
        assertThat(version.publishedBy()).isEqualTo("user@tenant");
        assertThat(version.publishedAt()).isNotNull();
    }

    @Test
    void rejectsAVersionNumberBelowOne() {
        assertThatThrownBy(() -> FlowVersion.publish(VERSION_ID, FLOW_ID, TENANT_ID, 0, "{}", "user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankGraph() {
        assertThatThrownBy(() -> FlowVersion.publish(VERSION_ID, FLOW_ID, TENANT_ID, 1, "  ", "user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withStateReturnsANewInstanceWithTheGivenState() {
        FlowVersion active = FlowVersion.publish(VERSION_ID, FLOW_ID, TENANT_ID, 1, "{}", "user");

        FlowVersion superseded = active.withState(FlowVersionState.PUBLISHED);

        assertThat(superseded.state()).isEqualTo(FlowVersionState.PUBLISHED);
        assertThat(superseded.versionNumber()).isEqualTo(active.versionNumber());
        assertThat(active.state()).isEqualTo(FlowVersionState.ACTIVE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd application && mvn -q -Dtest=FlowVersionTest test`
Expected: FAIL — `FlowVersion`/`FlowVersionState` do not exist yet.

- [ ] **Step 3: Write the minimal implementation**

Create `application/src/main/java/com/cl2/integration/domain/model/FlowVersionState.java`:

```java
package com.cl2.integration.domain.model;

public enum FlowVersionState {
    ACTIVE,
    PUBLISHED,
    ROLLED_BACK
}
```

Create `application/src/main/java/com/cl2/integration/application/exception/FlowNotFoundException.java`:

```java
package com.cl2.integration.application.exception;

public class FlowNotFoundException extends RuntimeException {

    public FlowNotFoundException(String message) {
        super(message);
    }
}
```

Create `application/src/main/java/com/cl2/integration/domain/model/FlowVersion.java`:

```java
package com.cl2.integration.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class FlowVersion {

    private final UUID id;
    private final UUID flowId;
    private final UUID tenantId;
    private final int versionNumber;
    private final String graph;
    private final FlowVersionState state;
    private final String publishedBy;
    private final Instant publishedAt;

    private FlowVersion(UUID id, UUID flowId, UUID tenantId, int versionNumber, String graph,
                        FlowVersionState state, String publishedBy, Instant publishedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be at least 1");
        }
        this.versionNumber = versionNumber;
        this.graph = requireNonBlank(graph, "graph");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.publishedBy = requireNonBlank(publishedBy, "publishedBy");
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }

    public static FlowVersion publish(UUID id, UUID flowId, UUID tenantId, int versionNumber, String graph,
                                       String publishedBy) {
        return new FlowVersion(id, flowId, tenantId, versionNumber, graph, FlowVersionState.ACTIVE, publishedBy,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    public static FlowVersion rehydrate(UUID id, UUID flowId, UUID tenantId, int versionNumber, String graph,
                                        FlowVersionState state, String publishedBy, Instant publishedAt) {
        return new FlowVersion(id, flowId, tenantId, versionNumber, graph, state, publishedBy, publishedAt);
    }

    public FlowVersion withState(FlowVersionState newState) {
        return new FlowVersion(id, flowId, tenantId, versionNumber, graph, newState, publishedBy, publishedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID flowId() {
        return flowId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public String graph() {
        return graph;
    }

    public FlowVersionState state() {
        return state;
    }

    public String publishedBy() {
        return publishedBy;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd application && mvn -q -Dtest=FlowVersionTest test`
Expected: PASS — 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/domain/model/FlowVersion.java \
        application/src/main/java/com/cl2/integration/domain/model/FlowVersionState.java \
        application/src/main/java/com/cl2/integration/application/exception/FlowNotFoundException.java \
        application/src/test/java/com/cl2/integration/domain/model/FlowVersionTest.java
git commit -m "feat: add FlowVersion domain model for immutable publish snapshots"
```

---

## Task 3: Domain repository ports

**Files:**
- Create: `application/src/main/java/com/cl2/integration/domain/port/FlowRepository.java`
- Create: `application/src/main/java/com/cl2/integration/domain/port/FlowVersionRepository.java`

No test for this task — these are interfaces with no behavior; they are
exercised by the fakes and adapters in later tasks.

**Interfaces:**
- Consumes: `Flow` (Task 1), `FlowVersion` (Task 2).
- Produces:
  `FlowRepository.save(UUID tenantId, Flow flow)`,
  `FlowRepository.findById(UUID tenantId, UUID id)`,
  `FlowRepository.findAll(UUID tenantId, boolean activeOnly)`,
  `FlowRepository.existsActive(UUID tenantId, String code)`;
  `FlowVersionRepository.save(UUID tenantId, FlowVersion version)`,
  `FlowVersionRepository.findAllByFlowId(UUID tenantId, UUID flowId)`,
  `FlowVersionRepository.findByFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber)` returning `Optional<FlowVersion>`,
  `FlowVersionRepository.findActiveByFlowId(UUID tenantId, UUID flowId)` returning `Optional<FlowVersion>`,
  `FlowVersionRepository.nextVersionNumber(UUID tenantId, UUID flowId)` returning `int`.

- [ ] **Step 1: Create the files**

Create `application/src/main/java/com/cl2/integration/domain/port/FlowRepository.java`:

```java
package com.cl2.integration.domain.port;

import com.cl2.integration.domain.model.Flow;
import java.util.List;
import java.util.UUID;

public interface FlowRepository {

    Flow save(UUID tenantId, Flow flow);

    Flow findById(UUID tenantId, UUID id);

    List<Flow> findAll(UUID tenantId, boolean activeOnly);

    boolean existsActive(UUID tenantId, String code);
}
```

Create `application/src/main/java/com/cl2/integration/domain/port/FlowVersionRepository.java`:

```java
package com.cl2.integration.domain.port;

import com.cl2.integration.domain.model.FlowVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowVersionRepository {

    FlowVersion save(UUID tenantId, FlowVersion version);

    List<FlowVersion> findAllByFlowId(UUID tenantId, UUID flowId);

    Optional<FlowVersion> findByFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber);

    Optional<FlowVersion> findActiveByFlowId(UUID tenantId, UUID flowId);

    int nextVersionNumber(UUID tenantId, UUID flowId);
}
```

- [ ] **Step 2: Verify the module still compiles**

Run: `cd application && mvn -q compile`
Expected: BUILD SUCCESS (interfaces have no implementers yet, so nothing
references them — compilation only checks syntax).

- [ ] **Step 3: Commit**

```bash
git add application/src/main/java/com/cl2/integration/domain/port/FlowRepository.java \
        application/src/main/java/com/cl2/integration/domain/port/FlowVersionRepository.java
git commit -m "feat: add FlowRepository and FlowVersionRepository ports"
```

---

## Task 4: Flyway migrations for `flow` and `flow_version`

**Files:**
- Create: `application/src/main/resources/db/migration/V11__create_flow.sql`
- Create: `application/src/main/resources/db/migration/V12__create_flow_version.sql`

**Interfaces:**
- Produces: MySQL tables `flow` (columns: `id`, `tenant_id`, `code`, `name`,
  `draft_graph`, `trigger_summary`, `active_version_number`, `archived`,
  `version`, `created_at`, `updated_at`) and `flow_version` (columns: `id`,
  `flow_id`, `tenant_id`, `version_number`, `graph`, `state`,
  `published_by`, `published_at`). Consumed by Task 5 and Task 6's JPA
  entities.

- [ ] **Step 1: Write the migrations**

Create `application/src/main/resources/db/migration/V11__create_flow.sql`:

```sql
CREATE TABLE flow (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    code VARCHAR(150) NOT NULL,
    name VARCHAR(200) NOT NULL,
    draft_graph JSON NULL,
    trigger_summary VARCHAR(100) NULL,
    active_version_number INT NULL,
    archived BOOLEAN NOT NULL,
    active_code_key TINYINT GENERATED ALWAYS AS (IF(archived, NULL, 1)) STORED,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_flow_tenant_archived (tenant_id, archived),
    UNIQUE KEY uq_flow_active_code (tenant_id, code, active_code_key)
);
```

Create `application/src/main/resources/db/migration/V12__create_flow_version.sql`:

```sql
CREATE TABLE flow_version (
    id BINARY(16) NOT NULL,
    flow_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    version_number INT NOT NULL,
    graph JSON NOT NULL,
    state VARCHAR(20) NOT NULL,
    published_by VARCHAR(255) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_flow_version_tenant_flow (tenant_id, flow_id),
    UNIQUE KEY uq_flow_version_number (flow_id, version_number),
    CONSTRAINT fk_flow_version_flow FOREIGN KEY (flow_id) REFERENCES flow (id)
);
```

The `active_code_key` generated column mirrors `integration_profile`'s
`active_profile_key` trick from `V1__create_integration_profile.sql`: it is
`1` when the flow is not archived and `NULL` when archived, so the unique
key only enforces uniqueness of `code` among *active* flows — an archived
flow's code can be reused by a new flow.

- [ ] **Step 2: Apply and verify the migration**

With the `integration-mysql` container running (`docker compose up -d
mysql` from the repo root), run:

```bash
cd application && mvn -q -Dtest=FlowTest test
```

This does not touch the database (Task 1's test is pure domain), but it
confirms the module still builds. To actually apply the migration, run any
test annotated `@SpringBootTest` (Task 7 will add one) — Flyway runs on
Spring context startup and fails the build if the SQL is invalid.

- [ ] **Step 3: Commit**

```bash
git add application/src/main/resources/db/migration/V11__create_flow.sql \
        application/src/main/resources/db/migration/V12__create_flow_version.sql
git commit -m "feat: add flow and flow_version Flyway migrations"
```

---

## Task 5: `Flow` JPA entity and Spring Data repository

**Files:**
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowJpaEntity.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataFlowRepository.java`

No standalone test — exercised by Task 7's `FlowPersistenceAdapterTest`.

**Interfaces:**
- Consumes: `Flow` (Task 1).
- Produces: `FlowJpaEntity.from(Flow flow)`, `flowJpaEntity.toDomain()`;
  `SpringDataFlowRepository.findByTenantIdAndId(UUID, UUID)` →
  `Optional<FlowJpaEntity>`,
  `findAllByTenantIdOrderByCreatedAtDesc(UUID)` → `List<FlowJpaEntity>`,
  `findAllByTenantIdAndArchivedFalseOrderByCreatedAtDesc(UUID)` →
  `List<FlowJpaEntity>`,
  `existsByTenantIdAndCodeAndArchivedFalse(UUID, String)` → `boolean`,
  `updateIfVersionMatches(UUID tenantId, UUID id, long expectedVersion, String name, String triggerSummary, String draftGraph, Integer activeVersionNumber, boolean archived, Instant updatedAt)` → `int` (rows updated).

- [ ] **Step 1: Write the entity**

Create `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowJpaEntity.java`:

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.Flow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "flow")
class FlowJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "code", nullable = false, length = 150)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "draft_graph", columnDefinition = "JSON")
    private String draftGraph;

    @Column(name = "trigger_summary", length = 100)
    private String triggerSummary;

    @Column(name = "active_version_number")
    private Integer activeVersionNumber;

    @Column(nullable = false)
    private boolean archived;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant updatedAt;

    protected FlowJpaEntity() {
    }

    private FlowJpaEntity(Flow flow) {
        this.id = flow.id();
        this.tenantId = flow.tenantId();
        this.code = flow.code();
        this.name = flow.name();
        this.draftGraph = flow.draftGraph();
        this.triggerSummary = flow.triggerSummary();
        this.activeVersionNumber = flow.activeVersionNumber();
        this.archived = flow.archived();
        this.version = flow.version();
        this.createdAt = toMysqlTimestamp(flow.createdAt());
        this.updatedAt = toMysqlTimestamp(flow.updatedAt());
    }

    static FlowJpaEntity from(Flow flow) {
        return new FlowJpaEntity(flow);
    }

    Flow toDomain() {
        return Flow.rehydrate(id, tenantId, code, name, draftGraph, triggerSummary, activeVersionNumber, archived,
                createdAt, updatedAt, version);
    }

    private static Instant toMysqlTimestamp(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
```

- [ ] **Step 2: Write the Spring Data repository**

Create `application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataFlowRepository.java`:

```java
package com.cl2.integration.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataFlowRepository extends Repository<FlowJpaEntity, UUID> {

    Optional<FlowJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<FlowJpaEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<FlowJpaEntity> findAllByTenantIdAndArchivedFalseOrderByCreatedAtDesc(UUID tenantId);

    boolean existsByTenantIdAndCodeAndArchivedFalse(UUID tenantId, String code);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FlowJpaEntity flow
            set flow.name = :name,
                flow.triggerSummary = :triggerSummary,
                flow.draftGraph = :draftGraph,
                flow.activeVersionNumber = :activeVersionNumber,
                flow.archived = :archived,
                flow.updatedAt = :updatedAt,
                flow.version = flow.version + 1
            where flow.tenantId = :tenantId
              and flow.id = :id
              and flow.version = :expectedVersion
            """)
    int updateIfVersionMatches(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("name") String name,
            @Param("triggerSummary") String triggerSummary,
            @Param("draftGraph") String draftGraph,
            @Param("activeVersionNumber") Integer activeVersionNumber,
            @Param("archived") boolean archived,
            @Param("updatedAt") Instant updatedAt);
}
```

- [ ] **Step 3: Verify the module compiles**

Run: `cd application && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowJpaEntity.java \
        application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataFlowRepository.java
git commit -m "feat: add Flow JPA entity and Spring Data repository"
```

---

## Task 6: `FlowVersion` JPA entity and Spring Data repository

**Files:**
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowVersionJpaEntity.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataFlowVersionRepository.java`

No standalone test — exercised by Task 8's `FlowVersionPersistenceAdapterTest`.

**Interfaces:**
- Consumes: `FlowVersion` (Task 2).
- Produces: `FlowVersionJpaEntity.from(FlowVersion version)`,
  `flowVersionJpaEntity.toDomain()`;
  `SpringDataFlowVersionRepository.findAllByTenantIdAndFlowIdOrderByVersionNumberDesc(UUID, UUID)` → `List<FlowVersionJpaEntity>`,
  `findByTenantIdAndFlowIdAndVersionNumber(UUID, UUID, int)` → `Optional<FlowVersionJpaEntity>`,
  `findByTenantIdAndFlowIdAndState(UUID, UUID, FlowVersionState)` → `Optional<FlowVersionJpaEntity>`,
  `countByTenantIdAndFlowId(UUID, UUID)` → `int`,
  `updateState(UUID tenantId, UUID id, FlowVersionState state)` (void, `@Modifying`).

- [ ] **Step 1: Write the entity**

Create `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowVersionJpaEntity.java`:

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.model.FlowVersionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "flow_version")
class FlowVersionJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "flow_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID flowId;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "graph", nullable = false, columnDefinition = "JSON")
    private String graph;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private FlowVersionState state;

    @Column(name = "published_by", nullable = false, length = 255)
    private String publishedBy;

    @Column(name = "published_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant publishedAt;

    protected FlowVersionJpaEntity() {
    }

    private FlowVersionJpaEntity(FlowVersion flowVersion) {
        this.id = flowVersion.id();
        this.flowId = flowVersion.flowId();
        this.tenantId = flowVersion.tenantId();
        this.versionNumber = flowVersion.versionNumber();
        this.graph = flowVersion.graph();
        this.state = flowVersion.state();
        this.publishedBy = flowVersion.publishedBy();
        this.publishedAt = flowVersion.publishedAt().truncatedTo(ChronoUnit.MICROS);
    }

    static FlowVersionJpaEntity from(FlowVersion flowVersion) {
        return new FlowVersionJpaEntity(flowVersion);
    }

    FlowVersion toDomain() {
        return FlowVersion.rehydrate(id, flowId, tenantId, versionNumber, graph, state, publishedBy, publishedAt);
    }
}
```

- [ ] **Step 2: Write the Spring Data repository**

Create `application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataFlowVersionRepository.java`:

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowVersionState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataFlowVersionRepository extends Repository<FlowVersionJpaEntity, UUID> {

    List<FlowVersionJpaEntity> findAllByTenantIdAndFlowIdOrderByVersionNumberDesc(UUID tenantId, UUID flowId);

    Optional<FlowVersionJpaEntity> findByTenantIdAndFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber);

    Optional<FlowVersionJpaEntity> findByTenantIdAndFlowIdAndState(UUID tenantId, UUID flowId, FlowVersionState state);

    int countByTenantIdAndFlowId(UUID tenantId, UUID flowId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FlowVersionJpaEntity v set v.state = :state where v.tenantId = :tenantId and v.id = :id")
    void updateState(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("state") FlowVersionState state);
}
```

- [ ] **Step 3: Verify the module compiles**

Run: `cd application && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowVersionJpaEntity.java \
        application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataFlowVersionRepository.java
git commit -m "feat: add FlowVersion JPA entity and Spring Data repository"
```

---

## Task 7: `FlowPersistenceAdapter`

**Files:**
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapter.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapterTest.java`

**Interfaces:**
- Consumes: `FlowRepository` (Task 3), `FlowJpaEntity`/`SpringDataFlowRepository` (Task 5).
- Produces: `FlowPersistenceAdapter implements FlowRepository` — a
  `@Repository`-annotated bean later injected into `FlowService` (Task 9).

**Prerequisite:** `docker compose up -d mysql` from the repo root (the test
connects to `localhost:3306`, matching `application-test.yml`).

- [ ] **Step 1: Write the failing test**

Create `application/src/test/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapterTest.java`:

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.domain.model.Flow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
class FlowPersistenceAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");

    @Autowired
    private FlowPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearFlows() {
        jdbcTemplate.update("DELETE FROM flow_version");
        jdbcTemplate.update("DELETE FROM flow");
    }

    @Test
    void savesAndReadsAFlowWithinItsTenant() {
        Flow flow = Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X");

        Flow saved = adapter.save(TENANT_ID, flow);
        Flow found = adapter.findById(TENANT_ID, saved.id());

        assertThat(found.code()).isEqualTo("flow/x");
        assertThat(found.name()).isEqualTo("X");
        assertThat(found.version()).isZero();
        assertThat(found.draftGraph()).isNull();
    }

    @Test
    void throwsNotFoundForAnotherTenantsFlow() {
        Flow flow = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));

        assertThatThrownBy(() -> adapter.findById(OTHER_TENANT_ID, flow.id()))
                .isInstanceOf(FlowNotFoundException.class);
    }

    @Test
    void updatesTheDraftWhenTheExpectedVersionMatches() {
        Flow flow = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));

        Flow updated = adapter.save(TENANT_ID, flow.updateDraft("X renamed", "CRON */5", "{\"nodes\":[]}", 0));

        assertThat(updated.version()).isEqualTo(1);
        Flow reloaded = adapter.findById(TENANT_ID, flow.id());
        assertThat(reloaded.name()).isEqualTo("X renamed");
        assertThat(reloaded.draftGraph()).isEqualTo("{\"nodes\":[]}");
        assertThat(reloaded.version()).isEqualTo(1);
    }

    @Test
    void rejectsAStaleVersionOnUpdate() {
        Flow flow = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));
        adapter.save(TENANT_ID, flow.updateDraft("X v1", null, null, 0));

        assertThatThrownBy(() -> adapter.save(TENANT_ID, flow.updateDraft("X v2 (stale)", null, null, 0)))
                .isInstanceOf(FlowConflictException.class);
    }

    @Test
    void enforcesUniqueCodePerTenantAmongActiveFlows() {
        adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/dup", "First"));

        assertThatThrownBy(() -> adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/dup", "Second")))
                .isInstanceOf(FlowConflictException.class);
    }

    @Test
    void allowsReusingACodeOnceTheOriginalFlowIsArchived() {
        Flow original = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/dup", "First"));
        adapter.save(TENANT_ID, original.archive());

        Flow reused = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/dup", "Second"));

        assertThat(reused.code()).isEqualTo("flow/dup");
    }

    @Test
    void listsOnlyNonArchivedFlowsWhenActiveOnlyIsTrue() {
        adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/a", "A"));
        Flow toArchive = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/b", "B"));
        adapter.save(TENANT_ID, toArchive.archive());

        List<Flow> active = adapter.findAll(TENANT_ID, true);
        List<Flow> all = adapter.findAll(TENANT_ID, false);

        assertThat(active).extracting(Flow::code).containsExactly("flow/a");
        assertThat(all).extracting(Flow::code).containsExactlyInAnyOrder("flow/a", "flow/b");
    }

    @Test
    void existsActiveIsScopedToTenantAndNonArchivedFlows() {
        adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));

        assertThat(adapter.existsActive(TENANT_ID, "flow/x")).isTrue();
        assertThat(adapter.existsActive(OTHER_TENANT_ID, "flow/x")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd application && mvn -q -Dtest=FlowPersistenceAdapterTest test`
Expected: FAIL — compilation error, `FlowPersistenceAdapter` does not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapter.java`:

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.port.FlowRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class FlowPersistenceAdapter implements FlowRepository {

    private final SpringDataFlowRepository repository;
    private final EntityManager entityManager;

    FlowPersistenceAdapter(SpringDataFlowRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public Flow save(UUID tenantId, Flow flow) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(flow, "flow must not be null");
        if (!tenantId.equals(flow.tenantId())) {
            throw new IllegalArgumentException("tenantId must match the flow tenantId");
        }
        try {
            if (flow.version() == 0) {
                FlowJpaEntity entity = FlowJpaEntity.from(flow);
                entityManager.persist(entity);
                entityManager.flush();
                return entity.toDomain();
            }

            int updatedRows = repository.updateIfVersionMatches(
                    flow.tenantId(), flow.id(), flow.version() - 1,
                    flow.name(), flow.triggerSummary(), flow.draftGraph(), flow.activeVersionNumber(),
                    flow.archived(), flow.updatedAt());
            if (updatedRows == 0) {
                throw new FlowConflictException("Flow version is stale");
            }
            return FlowJpaEntity.from(flow).toDomain();
        } catch (DataIntegrityViolationException | ConstraintViolationException exception) {
            throw new FlowConflictException("Flow conflicts with an existing flow");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Flow findById(UUID tenantId, UUID id) {
        return repository.findByTenantIdAndId(tenantId, id)
                .map(FlowJpaEntity::toDomain)
                .orElseThrow(() -> new FlowNotFoundException("Flow " + id + " was not found for tenant " + tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Flow> findAll(UUID tenantId, boolean activeOnly) {
        List<FlowJpaEntity> flows = activeOnly
                ? repository.findAllByTenantIdAndArchivedFalseOrderByCreatedAtDesc(tenantId)
                : repository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        return flows.stream().map(FlowJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActive(UUID tenantId, String code) {
        return repository.existsByTenantIdAndCodeAndArchivedFalse(tenantId, code);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd application && mvn -q -Dtest=FlowPersistenceAdapterTest test`
Expected: PASS — 8 tests green. Flyway will have applied `V11`/`V12`
against the running `integration-mysql` container as part of Spring context
startup; if this fails with a migration checksum/SQL error, fix the SQL from
Task 4 before proceeding.

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapter.java \
        application/src/test/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapterTest.java
git commit -m "feat: add FlowPersistenceAdapter with optimistic locking"
```

---

## Task 8: `FlowVersionPersistenceAdapter`

**Files:**
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowVersionPersistenceAdapter.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/out/persistence/FlowVersionPersistenceAdapterTest.java`

**Interfaces:**
- Consumes: `FlowVersionRepository` (Task 3),
  `FlowVersionJpaEntity`/`SpringDataFlowVersionRepository` (Task 6),
  `FlowPersistenceAdapter` (Task 7, used only in the test to create a parent
  `flow` row satisfying the `fk_flow_version_flow` foreign key).
- Produces: `FlowVersionPersistenceAdapter implements FlowVersionRepository`
  — a `@Repository`-annotated bean later injected into `FlowService`
  (Task 10).

- [ ] **Step 1: Write the failing test**

Create `application/src/test/java/com/cl2/integration/adapter/out/persistence/FlowVersionPersistenceAdapterTest.java`:

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.model.FlowVersionState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class FlowVersionPersistenceAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

    @Autowired
    private FlowVersionPersistenceAdapter versionAdapter;

    @Autowired
    private FlowPersistenceAdapter flowAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID flowId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM flow_version");
        jdbcTemplate.update("DELETE FROM flow");
        Flow flow = flowAdapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));
        flowId = flow.id();
    }

    @Test
    void savesAndListsVersionsMostRecentFirst() {
        versionAdapter.save(TENANT_ID, FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 1, "{\"v\":1}", "user"));
        versionAdapter.save(TENANT_ID, FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 2, "{\"v\":2}", "user"));

        List<FlowVersion> versions = versionAdapter.findAllByFlowId(TENANT_ID, flowId);

        assertThat(versions).extracting(FlowVersion::versionNumber).containsExactly(2, 1);
    }

    @Test
    void findsAVersionByFlowIdAndVersionNumber() {
        versionAdapter.save(TENANT_ID, FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 1, "{}", "user"));

        Optional<FlowVersion> found = versionAdapter.findByFlowIdAndVersionNumber(TENANT_ID, flowId, 1);

        assertThat(found).isPresent();
        assertThat(found.get().state()).isEqualTo(FlowVersionState.ACTIVE);
    }

    @Test
    void findsTheActiveVersionAndUpdatingItsStatePersists() {
        FlowVersion saved = versionAdapter.save(TENANT_ID,
                FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 1, "{}", "user"));

        versionAdapter.save(TENANT_ID, saved.withState(FlowVersionState.PUBLISHED));

        assertThat(versionAdapter.findActiveByFlowId(TENANT_ID, flowId)).isEmpty();
        Optional<FlowVersion> reloaded = versionAdapter.findByFlowIdAndVersionNumber(TENANT_ID, flowId, 1);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().state()).isEqualTo(FlowVersionState.PUBLISHED);
    }

    @Test
    void nextVersionNumberStartsAtOneAndIncrements() {
        assertThat(versionAdapter.nextVersionNumber(TENANT_ID, flowId)).isEqualTo(1);

        versionAdapter.save(TENANT_ID, FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 1, "{}", "user"));

        assertThat(versionAdapter.nextVersionNumber(TENANT_ID, flowId)).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd application && mvn -q -Dtest=FlowVersionPersistenceAdapterTest test`
Expected: FAIL — compilation error, `FlowVersionPersistenceAdapter` does
not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowVersionPersistenceAdapter.java`:

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.model.FlowVersionState;
import com.cl2.integration.domain.port.FlowVersionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class FlowVersionPersistenceAdapter implements FlowVersionRepository {

    private final SpringDataFlowVersionRepository repository;
    private final EntityManager entityManager;

    FlowVersionPersistenceAdapter(SpringDataFlowVersionRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public FlowVersion save(UUID tenantId, FlowVersion version) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        if (!tenantId.equals(version.tenantId())) {
            throw new IllegalArgumentException("tenantId must match the version tenantId");
        }
        Optional<FlowVersionJpaEntity> existing = repository.findByTenantIdAndFlowIdAndVersionNumber(
                tenantId, version.flowId(), version.versionNumber());
        if (existing.isPresent()) {
            repository.updateState(tenantId, existing.get().toDomain().id(), version.state());
            return version;
        }
        FlowVersionJpaEntity entity = FlowVersionJpaEntity.from(version);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlowVersion> findAllByFlowId(UUID tenantId, UUID flowId) {
        return repository.findAllByTenantIdAndFlowIdOrderByVersionNumberDesc(tenantId, flowId).stream()
                .map(FlowVersionJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FlowVersion> findByFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber) {
        return repository.findByTenantIdAndFlowIdAndVersionNumber(tenantId, flowId, versionNumber)
                .map(FlowVersionJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FlowVersion> findActiveByFlowId(UUID tenantId, UUID flowId) {
        return repository.findByTenantIdAndFlowIdAndState(tenantId, flowId, FlowVersionState.ACTIVE)
                .map(FlowVersionJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public int nextVersionNumber(UUID tenantId, UUID flowId) {
        return repository.countByTenantIdAndFlowId(tenantId, flowId) + 1;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd application && mvn -q -Dtest=FlowVersionPersistenceAdapterTest test`
Expected: PASS — 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowVersionPersistenceAdapter.java \
        application/src/test/java/com/cl2/integration/adapter/out/persistence/FlowVersionPersistenceAdapterTest.java
git commit -m "feat: add FlowVersionPersistenceAdapter"
```

---

## Task 9: `FlowService` — create, list, get, updateDraft, archive

**Files:**
- Create: `application/src/main/java/com/cl2/integration/application/FlowView.java`
- Create: `application/src/main/java/com/cl2/integration/application/FlowVersionView.java`
- Create: `application/src/main/java/com/cl2/integration/application/command/CreateFlowCommand.java`
- Create: `application/src/main/java/com/cl2/integration/application/command/UpdateFlowDraftCommand.java`
- Create: `application/src/main/java/com/cl2/integration/application/FlowService.java`
- Test: `application/src/test/java/com/cl2/integration/application/FlowServiceTest.java`

**Interfaces:**
- Consumes: `Flow`/`FlowStatus` (Task 1), `FlowVersion`/`FlowVersionState`
  (Task 2), `FlowRepository`/`FlowVersionRepository` (Task 3).
- Produces: `FlowView(UUID id, UUID tenantId, String code, String name, String draftGraph, String triggerSummary, Integer activeVersionNumber, FlowStatus status, int nodeCount, boolean archived, Instant createdAt, Instant updatedAt, long version)`;
  `FlowVersionView(UUID id, UUID flowId, int versionNumber, String graph, FlowVersionState state, String publishedBy, Instant publishedAt)`;
  `CreateFlowCommand(String code, String name)`;
  `UpdateFlowDraftCommand(String name, String triggerSummary, String draftGraph, long expectedVersion)`;
  `FlowService.create(UUID tenantId, CreateFlowCommand command)` → `FlowView`,
  `FlowService.list(UUID tenantId, boolean activeOnly)` → `List<FlowView>`,
  `FlowService.get(UUID tenantId, UUID flowId)` → `FlowView`,
  `FlowService.updateDraft(UUID tenantId, UUID flowId, UpdateFlowDraftCommand command)` → `FlowView`,
  `FlowService.archive(UUID tenantId, UUID flowId)` → `void`.
  (`publish`/`rollback`/`listVersions` are added in Task 10 — this task's
  `FlowService` constructor already takes `FlowVersionRepository` so Task 10
  only adds methods, no constructor change.)

- [ ] **Step 1: Write the failing test**

Create `application/src/test/java/com/cl2/integration/application/FlowServiceTest.java`:

```java
package com.cl2.integration.application;

import com.cl2.integration.application.command.CreateFlowCommand;
import com.cl2.integration.application.command.UpdateFlowDraftCommand;
import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowStatus;
import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.port.FlowRepository;
import com.cl2.integration.domain.port.FlowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");

    private FakeFlowRepository flowRepository;
    private FakeFlowVersionRepository flowVersionRepository;
    private FlowService service;

    @BeforeEach
    void setUp() {
        flowRepository = new FakeFlowRepository();
        flowVersionRepository = new FakeFlowVersionRepository();
        service = new FlowService(flowRepository, flowVersionRepository, new ObjectMapper());
    }

    @Test
    void createsADraftFlowForTheSuppliedTenant() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        assertThat(created.tenantId()).isEqualTo(TENANT_ID);
        assertThat(created.code()).isEqualTo("flow/x");
        assertThat(created.status()).isEqualTo(FlowStatus.DRAFT);
        assertThat(created.nodeCount()).isZero();
    }

    @Test
    void rejectsCreatingADuplicateActiveCodeForTheSameTenant() {
        service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        assertThatThrownBy(() -> service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X again")))
                .isInstanceOf(FlowConflictException.class);
    }

    @Test
    void allowsTheSameCodeForDifferentTenants() {
        service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        FlowView createdForOtherTenant = service.create(OTHER_TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        assertThat(createdForOtherTenant.tenantId()).isEqualTo(OTHER_TENANT_ID);
    }

    @Test
    void listsFlowsScopedToTheTenant() {
        service.create(TENANT_ID, new CreateFlowCommand("flow/a", "A"));
        service.create(OTHER_TENANT_ID, new CreateFlowCommand("flow/b", "B"));

        List<FlowView> flows = service.list(TENANT_ID, true);

        assertThat(flows).extracting(FlowView::code).containsExactly("flow/a");
    }

    @Test
    void getsAFlowScopedToTheTenant() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        FlowView found = service.get(TENANT_ID, created.id());

        assertThat(found.id()).isEqualTo(created.id());
        assertThatThrownBy(() -> service.get(OTHER_TENANT_ID, created.id()))
                .isInstanceOf(FlowNotFoundException.class);
    }

    @Test
    void updateDraftReplacesNameTriggerAndGraphAndCountsNodes() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        FlowView updated = service.updateDraft(TENANT_ID, created.id(),
                new UpdateFlowDraftCommand("X renamed", "CRON */5", "{\"nodes\":[{\"id\":\"n1\"},{\"id\":\"n2\"}]}", 0));

        assertThat(updated.name()).isEqualTo("X renamed");
        assertThat(updated.triggerSummary()).isEqualTo("CRON */5");
        assertThat(updated.nodeCount()).isEqualTo(2);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void archiveMarksTheFlowObsolete() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        service.archive(TENANT_ID, created.id());

        assertThat(service.get(TENANT_ID, created.id()).status()).isEqualTo(FlowStatus.OBSOLETE);
    }

    private static final class FakeFlowRepository implements FlowRepository {

        private final Map<UUID, Flow> flows = new HashMap<>();

        @Override
        public Flow save(UUID tenantId, Flow flow) {
            if (!tenantId.equals(flow.tenantId())) {
                throw new IllegalArgumentException("tenantId must match the flow tenantId");
            }
            flows.put(flow.id(), flow);
            return flow;
        }

        @Override
        public Flow findById(UUID tenantId, UUID id) {
            Flow flow = flows.get(id);
            if (flow == null || !flow.tenantId().equals(tenantId)) {
                throw new FlowNotFoundException("Flow was not found");
            }
            return flow;
        }

        @Override
        public List<Flow> findAll(UUID tenantId, boolean activeOnly) {
            return flows.values().stream()
                    .filter(flow -> flow.tenantId().equals(tenantId))
                    .filter(flow -> !activeOnly || !flow.archived())
                    .toList();
        }

        @Override
        public boolean existsActive(UUID tenantId, String code) {
            return flows.values().stream()
                    .anyMatch(flow -> flow.tenantId().equals(tenantId) && flow.code().equals(code) && !flow.archived());
        }
    }

    private static final class FakeFlowVersionRepository implements FlowVersionRepository {

        private final List<FlowVersion> versions = new ArrayList<>();

        @Override
        public FlowVersion save(UUID tenantId, FlowVersion version) {
            versions.removeIf(v -> v.id().equals(version.id()));
            versions.add(version);
            return version;
        }

        @Override
        public List<FlowVersion> findAllByFlowId(UUID tenantId, UUID flowId) {
            return versions.stream()
                    .filter(v -> v.tenantId().equals(tenantId) && v.flowId().equals(flowId))
                    .sorted((a, b) -> b.versionNumber() - a.versionNumber())
                    .toList();
        }

        @Override
        public Optional<FlowVersion> findByFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber) {
            return versions.stream()
                    .filter(v -> v.tenantId().equals(tenantId) && v.flowId().equals(flowId) && v.versionNumber() == versionNumber)
                    .findFirst();
        }

        @Override
        public Optional<FlowVersion> findActiveByFlowId(UUID tenantId, UUID flowId) {
            return versions.stream()
                    .filter(v -> v.tenantId().equals(tenantId) && v.flowId().equals(flowId)
                            && v.state() == com.cl2.integration.domain.model.FlowVersionState.ACTIVE)
                    .findFirst();
        }

        @Override
        public int nextVersionNumber(UUID tenantId, UUID flowId) {
            return (int) versions.stream()
                    .filter(v -> v.tenantId().equals(tenantId) && v.flowId().equals(flowId))
                    .count() + 1;
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd application && mvn -q -Dtest=FlowServiceTest test`
Expected: FAIL — compilation error, `FlowService`/`FlowView`/`CreateFlowCommand`/`UpdateFlowDraftCommand` do not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `application/src/main/java/com/cl2/integration/application/FlowView.java`:

```java
package com.cl2.integration.application;

import com.cl2.integration.domain.model.FlowStatus;
import java.time.Instant;
import java.util.UUID;

public record FlowView(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        String draftGraph,
        String triggerSummary,
        Integer activeVersionNumber,
        FlowStatus status,
        int nodeCount,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
```

Create `application/src/main/java/com/cl2/integration/application/FlowVersionView.java`:

```java
package com.cl2.integration.application;

import com.cl2.integration.domain.model.FlowVersionState;
import java.time.Instant;
import java.util.UUID;

public record FlowVersionView(
        UUID id,
        UUID flowId,
        int versionNumber,
        String graph,
        FlowVersionState state,
        String publishedBy,
        Instant publishedAt) {
}
```

Create `application/src/main/java/com/cl2/integration/application/command/CreateFlowCommand.java`:

```java
package com.cl2.integration.application.command;

public record CreateFlowCommand(String code, String name) {
}
```

Create `application/src/main/java/com/cl2/integration/application/command/UpdateFlowDraftCommand.java`:

```java
package com.cl2.integration.application.command;

public record UpdateFlowDraftCommand(String name, String triggerSummary, String draftGraph, long expectedVersion) {
}
```

Create `application/src/main/java/com/cl2/integration/application/FlowService.java`:

```java
package com.cl2.integration.application;

import com.cl2.integration.application.command.CreateFlowCommand;
import com.cl2.integration.application.command.UpdateFlowDraftCommand;
import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.port.FlowRepository;
import com.cl2.integration.domain.port.FlowVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowService {

    private final FlowRepository flowRepository;
    private final FlowVersionRepository flowVersionRepository;
    private final ObjectMapper objectMapper;

    public FlowService(FlowRepository flowRepository, FlowVersionRepository flowVersionRepository,
                        ObjectMapper objectMapper) {
        this.flowRepository = flowRepository;
        this.flowVersionRepository = flowVersionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FlowView create(UUID tenantId, CreateFlowCommand command) {
        if (flowRepository.existsActive(tenantId, command.code())) {
            throw new FlowConflictException("A flow already exists with this code for the tenant");
        }
        Flow flow = Flow.create(UUID.randomUUID(), tenantId, command.code(), command.name());
        return toView(flowRepository.save(tenantId, flow));
    }

    @Transactional(readOnly = true)
    public List<FlowView> list(UUID tenantId, boolean activeOnly) {
        return flowRepository.findAll(tenantId, activeOnly).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public FlowView get(UUID tenantId, UUID flowId) {
        return toView(flowRepository.findById(tenantId, flowId));
    }

    @Transactional
    public FlowView updateDraft(UUID tenantId, UUID flowId, UpdateFlowDraftCommand command) {
        Flow flow = flowRepository.findById(tenantId, flowId);
        Flow updated = flow.updateDraft(command.name(), command.triggerSummary(), command.draftGraph(),
                command.expectedVersion());
        return toView(flowRepository.save(tenantId, updated));
    }

    @Transactional
    public void archive(UUID tenantId, UUID flowId) {
        Flow flow = flowRepository.findById(tenantId, flowId);
        flowRepository.save(tenantId, flow.archive());
    }

    FlowView toView(Flow flow) {
        return new FlowView(flow.id(), flow.tenantId(), flow.code(), flow.name(), flow.draftGraph(),
                flow.triggerSummary(), flow.activeVersionNumber(), flow.status(), countNodes(flow.draftGraph()),
                flow.archived(), flow.createdAt(), flow.updatedAt(), flow.version());
    }

    private int countNodes(String draftGraph) {
        if (draftGraph == null || draftGraph.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(draftGraph);
            JsonNode nodes = root.get("nodes");
            return nodes != null && nodes.isArray() ? nodes.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
```

`flowRepository`/`flowVersionRepository`/`toView` are package-private
(default access) rather than `private` for `toView`/fields where Task 10
needs to add `publish`/`rollback`/`listVersions` methods to this same class
— they stay in the same file, so this is purely about keeping the class
readable; Task 10 edits this file directly, no cross-file access is needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd application && mvn -q -Dtest=FlowServiceTest test`
Expected: PASS — 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/application/FlowView.java \
        application/src/main/java/com/cl2/integration/application/FlowVersionView.java \
        application/src/main/java/com/cl2/integration/application/command/CreateFlowCommand.java \
        application/src/main/java/com/cl2/integration/application/command/UpdateFlowDraftCommand.java \
        application/src/main/java/com/cl2/integration/application/FlowService.java \
        application/src/test/java/com/cl2/integration/application/FlowServiceTest.java
git commit -m "feat: add FlowService create/list/get/updateDraft/archive"
```

---

## Task 10: `FlowService` — publish and rollback

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/application/FlowService.java`
- Modify: `application/src/test/java/com/cl2/integration/application/FlowServiceTest.java`

**Interfaces:**
- Consumes: everything from Task 9 (same class, same fakes).
- Produces: `FlowService.listVersions(UUID tenantId, UUID flowId)` →
  `List<FlowVersionView>`,
  `FlowService.publish(UUID tenantId, UUID flowId, String publishedBy)` →
  `FlowVersionView`,
  `FlowService.rollback(UUID tenantId, UUID flowId, int versionNumber)` →
  `FlowVersionView`. Consumed by `FlowController` (Task 11).

- [ ] **Step 1: Write the failing tests**

Append to `application/src/test/java/com/cl2/integration/application/FlowServiceTest.java`, inside the `FlowServiceTest` class body (before the closing brace, alongside the other `@Test` methods):

```java
    @Test
    void publishRejectsAnEmptyDraft() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        assertThatThrownBy(() -> service.publish(TENANT_ID, created.id(), "user@tenant"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishCreatesVersionOneAndMarksTheFlowPublished() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));
        service.updateDraft(TENANT_ID, created.id(),
                new UpdateFlowDraftCommand("X", null, "{\"nodes\":[{\"id\":\"n1\"}]}", 0));

        FlowVersionView published = service.publish(TENANT_ID, created.id(), "user@tenant");

        assertThat(published.versionNumber()).isEqualTo(1);
        assertThat(published.state()).isEqualTo(com.cl2.integration.domain.model.FlowVersionState.ACTIVE);
        assertThat(published.publishedBy()).isEqualTo("user@tenant");
        assertThat(service.get(TENANT_ID, created.id()).status()).isEqualTo(FlowStatus.PUBLISHED);
        assertThat(service.get(TENANT_ID, created.id()).activeVersionNumber()).isEqualTo(1);
    }

    @Test
    void publishingASecondVersionSupersedesTheFirst() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));
        service.updateDraft(TENANT_ID, created.id(), new UpdateFlowDraftCommand("X", null, "{\"nodes\":[]}", 0));
        service.publish(TENANT_ID, created.id(), "user@tenant");
        service.updateDraft(TENANT_ID, created.id(), new UpdateFlowDraftCommand("X", null, "{\"nodes\":[{\"id\":\"n1\"}]}", 1));

        FlowVersionView secondVersion = service.publish(TENANT_ID, created.id(), "user@tenant");

        assertThat(secondVersion.versionNumber()).isEqualTo(2);
        List<FlowVersionView> versions = service.listVersions(TENANT_ID, created.id());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNumber()).isEqualTo(2);
        assertThat(versions.get(0).state()).isEqualTo(com.cl2.integration.domain.model.FlowVersionState.ACTIVE);
        assertThat(versions.get(1).versionNumber()).isEqualTo(1);
        assertThat(versions.get(1).state()).isEqualTo(com.cl2.integration.domain.model.FlowVersionState.PUBLISHED);
    }

    @Test
    void rollbackReactivatesAnOlderVersionAndMarksTheCurrentOneRolledBack() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));
        service.updateDraft(TENANT_ID, created.id(), new UpdateFlowDraftCommand("X", null, "{\"nodes\":[]}", 0));
        service.publish(TENANT_ID, created.id(), "user@tenant");
        service.updateDraft(TENANT_ID, created.id(), new UpdateFlowDraftCommand("X", null, "{\"nodes\":[{\"id\":\"n1\"}]}", 1));
        service.publish(TENANT_ID, created.id(), "user@tenant");

        FlowVersionView rolledBackTo = service.rollback(TENANT_ID, created.id(), 1);

        assertThat(rolledBackTo.versionNumber()).isEqualTo(1);
        assertThat(rolledBackTo.state()).isEqualTo(com.cl2.integration.domain.model.FlowVersionState.ACTIVE);
        List<FlowVersionView> versions = service.listVersions(TENANT_ID, created.id());
        FlowVersionView versionTwo = versions.stream().filter(v -> v.versionNumber() == 2).findFirst().orElseThrow();
        assertThat(versionTwo.state()).isEqualTo(com.cl2.integration.domain.model.FlowVersionState.ROLLED_BACK);
        assertThat(service.get(TENANT_ID, created.id()).activeVersionNumber()).isEqualTo(1);
    }

    @Test
    void rollbackToAnUnknownVersionThrowsNotFound() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        assertThatThrownBy(() -> service.rollback(TENANT_ID, created.id(), 99))
                .isInstanceOf(FlowNotFoundException.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd application && mvn -q -Dtest=FlowServiceTest test`
Expected: FAIL — compilation error, `FlowService` has no `publish`/`rollback`/`listVersions` methods yet.

- [ ] **Step 3: Write the minimal implementation**

Edit `application/src/main/java/com/cl2/integration/application/FlowService.java`.
Add these imports alongside the existing ones:

```java
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.model.FlowVersionState;
```

Add these methods to the `FlowService` class, after `archive(...)` and
before the `toView`/`countNodes` helpers:

```java
    @Transactional(readOnly = true)
    public List<FlowVersionView> listVersions(UUID tenantId, UUID flowId) {
        flowRepository.findById(tenantId, flowId);
        return flowVersionRepository.findAllByFlowId(tenantId, flowId).stream().map(this::toVersionView).toList();
    }

    @Transactional
    public FlowVersionView publish(UUID tenantId, UUID flowId, String publishedBy) {
        Flow flow = flowRepository.findById(tenantId, flowId);
        if (flow.draftGraph() == null || flow.draftGraph().isBlank()) {
            throw new IllegalArgumentException("Cannot publish a flow with an empty draft graph");
        }
        flowVersionRepository.findActiveByFlowId(tenantId, flowId)
                .ifPresent(active -> flowVersionRepository.save(tenantId, active.withState(FlowVersionState.PUBLISHED)));

        int nextVersionNumber = flowVersionRepository.nextVersionNumber(tenantId, flowId);
        FlowVersion published = flowVersionRepository.save(tenantId,
                FlowVersion.publish(UUID.randomUUID(), flowId, tenantId, nextVersionNumber, flow.draftGraph(), publishedBy));
        flowRepository.save(tenantId, flow.withActiveVersion(nextVersionNumber));
        return toVersionView(published);
    }

    @Transactional
    public FlowVersionView rollback(UUID tenantId, UUID flowId, int versionNumber) {
        Flow flow = flowRepository.findById(tenantId, flowId);
        FlowVersion target = flowVersionRepository.findByFlowIdAndVersionNumber(tenantId, flowId, versionNumber)
                .orElseThrow(() -> new FlowNotFoundException("Flow version " + versionNumber + " was not found"));

        flowVersionRepository.findActiveByFlowId(tenantId, flowId)
                .filter(active -> active.versionNumber() != versionNumber)
                .ifPresent(active -> flowVersionRepository.save(tenantId, active.withState(FlowVersionState.ROLLED_BACK)));

        FlowVersion reactivated = flowVersionRepository.save(tenantId, target.withState(FlowVersionState.ACTIVE));
        flowRepository.save(tenantId, flow.withActiveVersion(versionNumber));
        return toVersionView(reactivated);
    }
```

Add this private helper next to `toView`/`countNodes`:

```java
    private FlowVersionView toVersionView(FlowVersion version) {
        return new FlowVersionView(version.id(), version.flowId(), version.versionNumber(), version.graph(),
                version.state(), version.publishedBy(), version.publishedAt());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd application && mvn -q -Dtest=FlowServiceTest test`
Expected: PASS — 12 tests green (7 from Task 9 + 5 new).

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/application/FlowService.java \
        application/src/test/java/com/cl2/integration/application/FlowServiceTest.java
git commit -m "feat: add FlowService publish and rollback"
```

---

## Task 11: `FlowController` and web DTOs

**Files:**
- Create: `application/src/main/java/com/cl2/integration/adapter/in/web/dto/CreateFlowRequest.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/in/web/dto/UpdateFlowDraftRequest.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowResponse.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowVersionResponse.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/in/web/FlowController.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/in/web/FlowControllerTest.java`

**Interfaces:**
- Consumes: `FlowService` (Tasks 9–10), `FlowView`/`FlowVersionView`
  (Task 9), `TenantContext` (existing).
- Produces: HTTP contract at `/api/v1/flows*` per the spec.

- [ ] **Step 1: Write the failing test**

Create `application/src/test/java/com/cl2/integration/adapter/in/web/FlowControllerTest.java`:

```java
package com.cl2.integration.adapter.in.web;

import com.cl2.integration.application.FlowService;
import com.cl2.integration.application.FlowVersionView;
import com.cl2.integration.application.FlowView;
import com.cl2.integration.application.command.CreateFlowCommand;
import com.cl2.integration.application.command.UpdateFlowDraftCommand;
import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.domain.model.FlowStatus;
import com.cl2.integration.domain.model.FlowVersionState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlowController.class)
class FlowControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID FLOW_ID = UUID.fromString("7b4fe930-a3ce-43c1-9297-ff7a3c60f80c");
    private static final String BASE_PATH = "/api/v1/flows";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FlowService service;

    @Test
    void createsAFlowForTheTenantFromTheHeader() throws Exception {
        given(service.create(eq(TENANT_ID), any(CreateFlowCommand.class))).willReturn(flowView());

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"flow/x","name":"X"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(FLOW_ID.toString()))
                .andExpect(jsonPath("$.code").value("flow/x"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        then(service).should().create(eq(TENANT_ID), any(CreateFlowCommand.class));
    }

    @Test
    void returns409WhenTheServiceReportsAConflict() throws Exception {
        given(service.create(eq(TENANT_ID), any(CreateFlowCommand.class)))
                .willThrow(new FlowConflictException("A flow already exists"));

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"flow/x","name":"X"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void listsFlowsForTheTenantFromTheHeader() throws Exception {
        given(service.list(eq(TENANT_ID), eq(true))).willReturn(List.of(flowView()));

        mockMvc.perform(get(BASE_PATH).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSizeOne()));

        then(service).should().list(TENANT_ID, true);
    }

    @Test
    void getsAFlowByIdForTheTenant() throws Exception {
        given(service.get(TENANT_ID, FLOW_ID)).willReturn(flowView());

        mockMvc.perform(get(BASE_PATH + "/{flowId}", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FLOW_ID.toString()));
    }

    @Test
    void returns404WhenTheFlowIsNotFound() throws Exception {
        given(service.get(TENANT_ID, FLOW_ID)).willThrow(new FlowNotFoundException("not found"));

        mockMvc.perform(get(BASE_PATH + "/{flowId}", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesTheDraftForTheTenant() throws Exception {
        given(service.updateDraft(eq(TENANT_ID), eq(FLOW_ID), any(UpdateFlowDraftCommand.class)))
                .willReturn(flowView());

        mockMvc.perform(put(BASE_PATH + "/{flowId}", FLOW_ID)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X renamed","triggerSummary":"CRON */5","draftGraph":{"nodes":[]},"expectedVersion":0}
                                """))
                .andExpect(status().isOk());

        then(service).should().updateDraft(eq(TENANT_ID), eq(FLOW_ID), any(UpdateFlowDraftCommand.class));
    }

    @Test
    void listsVersionsForAFlow() throws Exception {
        given(service.listVersions(TENANT_ID, FLOW_ID)).willReturn(List.of(flowVersionView()));

        mockMvc.perform(get(BASE_PATH + "/{flowId}/versions", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNumber").value(1));
    }

    @Test
    void publishesTheCurrentDraft() throws Exception {
        given(service.publish(eq(TENANT_ID), eq(FLOW_ID), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(flowVersionView());

        mockMvc.perform(post(BASE_PATH + "/{flowId}/versions/publish", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    @Test
    void rollsBackToAnOlderVersion() throws Exception {
        given(service.rollback(TENANT_ID, FLOW_ID, 1)).willReturn(flowVersionView());

        mockMvc.perform(post(BASE_PATH + "/{flowId}/versions/{versionNumber}/rollback", FLOW_ID, 1)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(1));
    }

    @Test
    void archivesAFlow() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/{flowId}", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNoContent());

        then(service).should().archive(TENANT_ID, FLOW_ID);
    }

    private static org.hamcrest.Matcher<Object> hasSizeOne() {
        return org.hamcrest.Matchers.hasSize(1);
    }

    private static FlowView flowView() {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        return new FlowView(FLOW_ID, TENANT_ID, "flow/x", "X", null, null, null, FlowStatus.DRAFT, 0, false, now, now, 0);
    }

    private static FlowVersionView flowVersionView() {
        return new FlowVersionView(UUID.randomUUID(), FLOW_ID, 1, "{}", FlowVersionState.ACTIVE, "user@tenant",
                Instant.parse("2026-08-30T00:00:00Z"));
    }
}
```

This test relies on `X-Tenant-ID` resolving into `TenantContext` — verify
this happens via a servlet filter already registered for
`@WebMvcTest`-scoped tests by checking that
`IntegrationProfileControllerTest` (read during design) passes the same
header and asserts `TENANT_ID` is what reaches the mocked service; if the
filter is `@Component`-scanned automatically it will already apply here
too, since `@WebMvcTest(FlowController.class)` loads all
`@ControllerAdvice`/`Filter`/`Converter` beans in the same package tree by
default. If this test fails with `TenantRequiredException` bubbling up as a
400 for every case instead of the expected status, check
`application/src/main/java/com/cl2/integration/infrastructure/tenant/`
for the filter class name and add
`@Import(<TenantFilterClassName>.class)` to this test class.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd application && mvn -q -Dtest=FlowControllerTest test`
Expected: FAIL — compilation error, `FlowController` and the DTOs do not
exist yet.

- [ ] **Step 3: Write the minimal implementation**

Create `application/src/main/java/com/cl2/integration/adapter/in/web/dto/CreateFlowRequest.java`:

```java
package com.cl2.integration.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateFlowRequest(@NotBlank String code, @NotBlank String name) {
}
```

Create `application/src/main/java/com/cl2/integration/adapter/in/web/dto/UpdateFlowDraftRequest.java`:

```java
package com.cl2.integration.adapter.in.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateFlowDraftRequest(
        @NotBlank String name,
        String triggerSummary,
        JsonNode draftGraph,
        @NotNull Long expectedVersion) {
}
```

Create `application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowResponse.java`:

```java
package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.FlowView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

public record FlowResponse(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode draftGraph,
        String triggerSummary,
        Integer activeVersionNumber,
        String status,
        int nodeCount,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static FlowResponse from(FlowView view, ObjectMapper objectMapper) {
        return new FlowResponse(view.id(), view.tenantId(), view.code(), view.name(),
                readTree(view.draftGraph(), objectMapper), view.triggerSummary(), view.activeVersionNumber(),
                view.status().name(), view.nodeCount(), view.archived(), view.createdAt(), view.updatedAt(),
                view.version());
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
```

Create `application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowVersionResponse.java`:

```java
package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.FlowVersionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

public record FlowVersionResponse(
        UUID id,
        UUID flowId,
        int versionNumber,
        JsonNode graph,
        String state,
        String publishedBy,
        Instant publishedAt) {

    public static FlowVersionResponse from(FlowVersionView view, ObjectMapper objectMapper) {
        JsonNode graph;
        try {
            graph = objectMapper.readTree(view.graph());
        } catch (Exception e) {
            graph = null;
        }
        return new FlowVersionResponse(view.id(), view.flowId(), view.versionNumber(), graph,
                view.state().name(), view.publishedBy(), view.publishedAt());
    }
}
```

Create `application/src/main/java/com/cl2/integration/adapter/in/web/FlowController.java`:

```java
package com.cl2.integration.adapter.in.web;

import com.cl2.integration.adapter.in.web.dto.CreateFlowRequest;
import com.cl2.integration.adapter.in.web.dto.FlowResponse;
import com.cl2.integration.adapter.in.web.dto.FlowVersionResponse;
import com.cl2.integration.adapter.in.web.dto.UpdateFlowDraftRequest;
import com.cl2.integration.application.FlowService;
import com.cl2.integration.application.command.CreateFlowCommand;
import com.cl2.integration.application.command.UpdateFlowDraftCommand;
import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flows")
public class FlowController {

    private final FlowService service;
    private final ObjectMapper objectMapper;

    public FlowController(FlowService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlowResponse create(@Valid @RequestBody CreateFlowRequest request) {
        return FlowResponse.from(service.create(TenantContext.requireTenantId(),
                new CreateFlowCommand(request.code(), request.name())), objectMapper);
    }

    @GetMapping
    public List<FlowResponse> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.list(TenantContext.requireTenantId(), activeOnly).stream()
                .map(view -> FlowResponse.from(view, objectMapper))
                .toList();
    }

    @GetMapping("/{flowId}")
    public FlowResponse get(@PathVariable UUID flowId) {
        return FlowResponse.from(service.get(TenantContext.requireTenantId(), flowId), objectMapper);
    }

    @PutMapping("/{flowId}")
    public FlowResponse updateDraft(@PathVariable UUID flowId, @Valid @RequestBody UpdateFlowDraftRequest request) {
        String draftGraph = request.draftGraph() != null ? request.draftGraph().toString() : null;
        return FlowResponse.from(service.updateDraft(TenantContext.requireTenantId(), flowId,
                new UpdateFlowDraftCommand(request.name(), request.triggerSummary(), draftGraph,
                        request.expectedVersion())), objectMapper);
    }

    @GetMapping("/{flowId}/versions")
    public List<FlowVersionResponse> listVersions(@PathVariable UUID flowId) {
        return service.listVersions(TenantContext.requireTenantId(), flowId).stream()
                .map(view -> FlowVersionResponse.from(view, objectMapper))
                .toList();
    }

    @PostMapping("/{flowId}/versions/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public FlowVersionResponse publish(@PathVariable UUID flowId, Principal principal) {
        String publishedBy = principal != null ? principal.getName() : "unknown";
        return FlowVersionResponse.from(service.publish(TenantContext.requireTenantId(), flowId, publishedBy),
                objectMapper);
    }

    @PostMapping("/{flowId}/versions/{versionNumber}/rollback")
    public FlowVersionResponse rollback(@PathVariable UUID flowId, @PathVariable int versionNumber) {
        return FlowVersionResponse.from(service.rollback(TenantContext.requireTenantId(), flowId, versionNumber),
                objectMapper);
    }

    @DeleteMapping("/{flowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID flowId) {
        service.archive(TenantContext.requireTenantId(), flowId);
    }
}
```

Edit `application/src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java`:
add these two imports next to the existing `IntegrationProfile*` exception
imports:

```java
import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
```

Add these two handler methods, next to `handleNotFound`/`handleConflict`:

```java
    @ExceptionHandler(FlowNotFoundException.class)
    ProblemDetail handleFlowNotFound(FlowNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "FLOW_NOT_FOUND", "Flow was not found", request);
    }

    @ExceptionHandler(FlowConflictException.class)
    ProblemDetail handleFlowConflict(FlowConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "FLOW_CONFLICT", "Flow conflicts with an existing flow", request);
    }
```

`IllegalArgumentException` (thrown by `publish` on an empty draft) is
already mapped to `400 Bad Request` by the existing `handleBadRequest`
handler — no change needed there.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd application && mvn -q -Dtest=FlowControllerTest test`
Expected: PASS — 10 tests green. If any test fails with 400 instead of the
expected status because `X-Tenant-ID` isn't reaching `TenantContext`, follow
the note under Step 1 to import the tenant-resolving filter/interceptor
into the `@WebMvcTest` slice (mirror whatever
`IntegrationProfileControllerTest` relies on for the same header).

- [ ] **Step 5: Run the full application test suite**

Run: `cd application && mvn -q test`
Expected: BUILD SUCCESS — confirms nothing in the existing suite (including
`ApiExceptionHandler`'s other handlers) broke.

- [ ] **Step 6: Commit**

```bash
git add application/src/main/java/com/cl2/integration/adapter/in/web/dto/CreateFlowRequest.java \
        application/src/main/java/com/cl2/integration/adapter/in/web/dto/UpdateFlowDraftRequest.java \
        application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowResponse.java \
        application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowVersionResponse.java \
        application/src/main/java/com/cl2/integration/adapter/in/web/FlowController.java \
        application/src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java \
        application/src/test/java/com/cl2/integration/adapter/in/web/FlowControllerTest.java
git commit -m "feat: add FlowController REST endpoints for /api/v1/flows"
```

---

## Task 12: BFF proxy routes for flows

**Files:**
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts`
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts`
- Modify: `backoffice/apps/bff/src/main.ts`
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts`

**Interfaces:**
- Consumes: `FlowController`'s HTTP contract (Task 11), reached at
  `${GATEWAY_URI}/api/v1/flows*` (the Java `application` service is exposed
  through the `gateway`, whose `application.yml` already routes all
  `/api/**` paths generically — no gateway change needed).
- Produces: `GatewayProxyService.{getFlows, getFlow, createFlow, updateFlow, listFlowVersions, publishFlow, rollbackFlow, archiveFlow}`,
  each proxied at `GatewayProxyController` under `bff/api/v1/flows*`.
  Consumed by `flow.service.ts` (Task 13).

- [ ] **Step 1: Write the failing test**

Edit `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts`.
Add these paths to **both** `exclude` arrays already in the file (the one
in this test's `beforeEach`, matching the list `main.ts` will get in Step
3) — insert them right after the `integration-profiles` entries:

```ts
        { path: 'bff/api/v1/flows', method: RequestMethod.GET },
        { path: 'bff/api/v1/flows', method: RequestMethod.POST },
        { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.GET },
        { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.PUT },
        { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.DELETE },
        { path: 'bff/api/v1/flows/:flowId/versions', method: RequestMethod.GET },
        { path: 'bff/api/v1/flows/:flowId/versions/publish', method: RequestMethod.POST },
        { path: 'bff/api/v1/flows/:flowId/versions/:versionNumber/rollback', method: RequestMethod.POST },
```

Add this test to the `describe('Gateway profile proxy', ...)` block (read
the existing tests in this file first to match how they mock `axios` and
assert on `session=authenticated`; add analogous cases):

```ts
  it('proxies flow creation with the session access token', async () => {
    const postSpy = jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'f-1', code: 'flow/x' } });

    const response = await request(app.getHttpServer())
      .post('/bff/api/v1/flows')
      .set('Cookie', 'session=authenticated')
      .send({ code: 'flow/x', name: 'X' });

    expect(response.status).toBe(HttpStatus.CREATED === undefined ? 201 : HttpStatus.CREATED);
    expect(postSpy).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/flows',
      { code: 'flow/x', name: 'X' },
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it('proxies flow listing with the session access token', async () => {
    const getSpy = jest.spyOn(axios, 'get').mockResolvedValue({ data: [] });

    await request(app.getHttpServer()).get('/bff/api/v1/flows').set('Cookie', 'session=authenticated');

    expect(getSpy).toHaveBeenCalledWith('http://gateway.internal/api/v1/flows', {
      headers: { Authorization: 'Bearer session-access-token' },
    });
  });

  it('proxies publishing a flow version', async () => {
    const postSpy = jest.spyOn(axios, 'post').mockResolvedValue({ data: { versionNumber: 1 } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/flows/f-1/versions/publish')
      .set('Cookie', 'session=authenticated');

    expect(postSpy).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/flows/f-1/versions/publish',
      {},
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });

  it('proxies rolling back a flow to an earlier version', async () => {
    const postSpy = jest.spyOn(axios, 'post').mockResolvedValue({ data: { versionNumber: 1 } });

    await request(app.getHttpServer())
      .post('/bff/api/v1/flows/f-1/versions/1/rollback')
      .set('Cookie', 'session=authenticated');

    expect(postSpy).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/flows/f-1/versions/1/rollback',
      {},
      { headers: { Authorization: 'Bearer session-access-token' } },
    );
  });
```

Read the actual first ~30 lines of the existing spec file to reuse its
exact `request`/`app` setup — the snippet above assumes the same `request`
(supertest), `app`, and cookie-session middleware already present; do not
duplicate the `beforeEach`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backoffice && npx nx test bff --skip-nx-cache`
Expected: FAIL — 404s, since no `/bff/api/v1/flows*` routes exist yet, and
the `exclude` list added in Step 1 references paths not yet registered
(harmless — `exclude` only matters once the route exists).

- [ ] **Step 3: Write the minimal implementation**

Edit `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts`. Add
these methods to the `GatewayProxyService` class, after `getCredentials`:

```ts
  getFlows(accessToken: string): Promise<unknown> {
    return this.forward('get', '/api/v1/flows', accessToken);
  }

  getFlow(accessToken: string, flowId: string): Promise<unknown> {
    return this.forward('get', `/api/v1/flows/${flowId}`, accessToken);
  }

  createFlow(accessToken: string, body: unknown): Promise<unknown> {
    return this.forward('post', '/api/v1/flows', accessToken, body);
  }

  updateFlow(accessToken: string, flowId: string, body: unknown): Promise<unknown> {
    return this.forward('put', `/api/v1/flows/${flowId}`, accessToken, body);
  }

  listFlowVersions(accessToken: string, flowId: string): Promise<unknown> {
    return this.forward('get', `/api/v1/flows/${flowId}/versions`, accessToken);
  }

  publishFlow(accessToken: string, flowId: string): Promise<unknown> {
    return this.forward('post', `/api/v1/flows/${flowId}/versions/publish`, accessToken, {});
  }

  rollbackFlow(accessToken: string, flowId: string, versionNumber: number): Promise<unknown> {
    return this.forward('post', `/api/v1/flows/${flowId}/versions/${versionNumber}/rollback`, accessToken, {});
  }

  archiveFlow(accessToken: string, flowId: string): Promise<unknown> {
    return this.forward('delete', `/api/v1/flows/${flowId}`, accessToken);
  }
```

Edit `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts`.
Add these endpoints to the `GatewayProxyController` class, after
`getCredentials`:

```ts
  @Get('flows')
  getFlows(@Req() request: AuthenticatedRequest) {
    return this.gatewayProxy.getFlows(request.session.tokens!.access_token!);
  }

  @Get('flows/:flowId')
  getFlow(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.getFlow(request.session.tokens!.access_token!, flowId);
  }

  @Post('flows')
  @HttpCode(HttpStatus.CREATED)
  createFlow(@Req() request: AuthenticatedRequest, @Body() body: unknown) {
    return this.gatewayProxy.createFlow(request.session.tokens!.access_token!, body);
  }

  @Put('flows/:flowId')
  updateFlow(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string, @Body() body: unknown) {
    return this.gatewayProxy.updateFlow(request.session.tokens!.access_token!, flowId, body);
  }

  @Get('flows/:flowId/versions')
  listFlowVersions(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.listFlowVersions(request.session.tokens!.access_token!, flowId);
  }

  @Post('flows/:flowId/versions/publish')
  @HttpCode(HttpStatus.CREATED)
  publishFlow(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.publishFlow(request.session.tokens!.access_token!, flowId);
  }

  @Post('flows/:flowId/versions/:versionNumber/rollback')
  rollbackFlow(
    @Req() request: AuthenticatedRequest,
    @Param('flowId') flowId: string,
    @Param('versionNumber') versionNumber: string,
  ) {
    return this.gatewayProxy.rollbackFlow(request.session.tokens!.access_token!, flowId, Number(versionNumber));
  }

  @Delete('flows/:flowId')
  @HttpCode(HttpStatus.NO_CONTENT)
  archiveFlow(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string) {
    return this.gatewayProxy.archiveFlow(request.session.tokens!.access_token!, flowId);
  }
```

Edit `backoffice/apps/bff/src/main.ts`. Add the same 8 path entries used in
Step 1 to the `exclude` array, right after the `integration-profiles`
entries:

```ts
      { path: 'bff/api/v1/flows', method: RequestMethod.GET },
      { path: 'bff/api/v1/flows', method: RequestMethod.POST },
      { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.GET },
      { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.PUT },
      { path: 'bff/api/v1/flows/:flowId', method: RequestMethod.DELETE },
      { path: 'bff/api/v1/flows/:flowId/versions', method: RequestMethod.GET },
      { path: 'bff/api/v1/flows/:flowId/versions/publish', method: RequestMethod.POST },
      { path: 'bff/api/v1/flows/:flowId/versions/:versionNumber/rollback', method: RequestMethod.POST },
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backoffice && npx nx test bff --skip-nx-cache`
Expected: PASS — all `gateway-proxy` tests green, including the 4 new ones.

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts \
        backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts \
        backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts \
        backoffice/apps/bff/src/main.ts
git commit -m "feat: proxy /api/v1/flows through the backoffice BFF"
```

---

## Task 13: Angular `flow.model.ts` and `flow.service.ts`

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow.model.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow.service.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow.service.spec.ts`

**Interfaces:**
- Consumes: nothing new (pure TypeScript + `HttpClient`, same pattern as
  `IntegrationProfileService`).
- Produces: `Flow { id, tenantId, code, name, draftGraph: unknown | null, triggerSummary: string | null, activeVersionNumber: number | null, status: FlowStatus, nodeCount: number, archived: boolean, createdAt, updatedAt, version }`,
  `FlowVersion { id, flowId, versionNumber, graph: unknown, state: FlowVersionState, publishedBy, publishedAt }`,
  `CreateFlowPayload { code, name }`,
  `UpdateFlowDraftPayload { name, triggerSummary, draftGraph, expectedVersion }`;
  `FlowService.{list, get, create, updateDraft, listVersions, publish, rollback, archive}`.
  Consumed by `flow-list.component.ts` and `flow-designer.component.ts`
  (Task 14, Task 15).

The current `execCount24h`/`errorRate`/`p95` fields are removed — they were
placeholders from the original UI-only skeleton and are out of scope per
the spec.

- [ ] **Step 1: Write the failing test**

Rewrite `backoffice/apps/integration-mfe/src/app/flow/flow.service.spec.ts`:

```ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FlowService } from './flow.service';

const FLOW = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: { nodes: [] },
  triggerSummary: 'CRON */5',
  activeVersionNumber: null,
  status: 'DRAFT',
  nodeCount: 0,
  archived: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
  version: 0,
};

const FLOW_VERSION = {
  id: 'v-1',
  flowId: 'f-1',
  versionNumber: 1,
  graph: { nodes: [] },
  state: 'ACTIVE',
  publishedBy: 'user@tenant',
  publishedAt: '2026-08-30T00:00:00Z',
};

describe('FlowService', () => {
  let service: FlowService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(FlowService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists flows through the BFF same-origin endpoint', () => {
    service.list().subscribe((flows) => expect(flows[0].id).toBe('f-1'));
    const request = http.expectOne('/bff/api/v1/flows');
    expect(request.request.method).toBe('GET');
    request.flush([FLOW]);
  });

  it('gets a single flow by id', () => {
    service.get('f-1').subscribe((flow) => expect(flow.code).toBe('flow/vehiculo-alta'));
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW);
  });

  it('creates a flow', () => {
    service.create({ code: 'flow/x', name: 'X' }).subscribe((flow) => expect(flow.id).toBe('f-1'));
    const request = http.expectOne('/bff/api/v1/flows');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ code: 'flow/x', name: 'X' });
    request.flush(FLOW);
  });

  it('updates the draft with the expected version', () => {
    service
      .updateDraft('f-1', { name: 'X renamed', triggerSummary: 'CRON */5', draftGraph: { nodes: [] }, expectedVersion: 0 })
      .subscribe();
    const request = http.expectOne('/bff/api/v1/flows/f-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.expectedVersion).toBe(0);
    request.flush(FLOW);
  });

  it('lists versions for a flow', () => {
    service.listVersions('f-1').subscribe((versions) => expect(versions[0].versionNumber).toBe(1));
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([FLOW_VERSION]);
  });

  it('publishes the current draft', () => {
    service.publish('f-1').subscribe((version) => expect(version.state).toBe('ACTIVE'));
    const request = http.expectOne('/bff/api/v1/flows/f-1/versions/publish');
    expect(request.request.method).toBe('POST');
    request.flush(FLOW_VERSION);
  });

  it('rolls back to an earlier version', () => {
    service.rollback('f-1', 1).subscribe((version) => expect(version.versionNumber).toBe(1));
    const request = http.expectOne('/bff/api/v1/flows/f-1/versions/1/rollback');
    expect(request.request.method).toBe('POST');
    request.flush(FLOW_VERSION);
  });

  it('archives a flow', () => {
    service.archive('f-1').subscribe();
    const request = http.expectOne('/bff/api/v1/flows/f-1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --skip-nx-cache`
Expected: FAIL — `FlowService` has no `get`/`create`/`updateDraft`/`listVersions`/`publish`/`rollback`/`archive` methods yet.

- [ ] **Step 3: Write the minimal implementation**

Rewrite `backoffice/apps/integration-mfe/src/app/flow/flow.model.ts`:

```ts
export type FlowStatus = 'DRAFT' | 'PUBLISHED' | 'OBSOLETE';
export type FlowVersionState = 'ACTIVE' | 'PUBLISHED' | 'ROLLED_BACK';

export interface Flow {
  id: string;
  tenantId: string;
  code: string;
  name: string;
  draftGraph: unknown | null;
  triggerSummary: string | null;
  activeVersionNumber: number | null;
  status: FlowStatus;
  nodeCount: number;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface FlowVersion {
  id: string;
  flowId: string;
  versionNumber: number;
  graph: unknown;
  state: FlowVersionState;
  publishedBy: string;
  publishedAt: string;
}

export interface CreateFlowPayload {
  code: string;
  name: string;
}

export interface UpdateFlowDraftPayload {
  name: string;
  triggerSummary: string | null;
  draftGraph: unknown | null;
  expectedVersion: number;
}
```

Rewrite `backoffice/apps/integration-mfe/src/app/flow/flow.service.ts`:

```ts
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateFlowPayload, Flow, FlowVersion, UpdateFlowDraftPayload } from './flow.model';

const BASE_URL = '/bff/api/v1/flows';

@Injectable({ providedIn: 'root' })
export class FlowService {
  private readonly http = inject(HttpClient);

  list(): Observable<Flow[]> {
    return this.http.get<Flow[]>(BASE_URL);
  }

  get(id: string): Observable<Flow> {
    return this.http.get<Flow>(`${BASE_URL}/${id}`);
  }

  create(payload: CreateFlowPayload): Observable<Flow> {
    return this.http.post<Flow>(BASE_URL, payload);
  }

  updateDraft(id: string, payload: UpdateFlowDraftPayload): Observable<Flow> {
    return this.http.put<Flow>(`${BASE_URL}/${id}`, payload);
  }

  listVersions(flowId: string): Observable<FlowVersion[]> {
    return this.http.get<FlowVersion[]>(`${BASE_URL}/${flowId}/versions`);
  }

  publish(flowId: string): Observable<FlowVersion> {
    return this.http.post<FlowVersion>(`${BASE_URL}/${flowId}/versions/publish`, {});
  }

  rollback(flowId: string, versionNumber: number): Observable<FlowVersion> {
    return this.http.post<FlowVersion>(`${BASE_URL}/${flowId}/versions/${versionNumber}/rollback`, {});
  }

  archive(flowId: string): Observable<void> {
    return this.http.delete<void>(`${BASE_URL}/${flowId}`);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --skip-nx-cache`
Expected: PASS — all `flow.service.spec.ts` tests green. Other specs
(`flow-list`, `flow-designer`, `flow-executions`, `flow-execution-detail`)
will now fail to compile because they reference the removed
`execCount24h`/`errorRate`/`p95` fields — this is expected; Tasks 14–15 fix
`flow-list`/`flow-designer`, and `flow-executions`/`flow-execution-detail`
don't reference those fields (they use `FlowExecution`, a separate,
untouched interface), so only `flow-list.component.spec.ts` needs a look —
confirm by running the full test command above and reading which specs
fail before starting Task 14.

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/flow/flow.model.ts \
        backoffice/apps/integration-mfe/src/app/flow/flow.service.ts \
        backoffice/apps/integration-mfe/src/app/flow/flow.service.spec.ts
git commit -m "feat: implement FlowService against the real /api/v1/flows contract"
```

---

## Task 14: Enable flow creation from the Flows list

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.spec.ts`

**Interfaces:**
- Consumes: `FlowService.list`/`create` (Task 13), `Flow` (Task 13).
- Produces: a working "＋ Nuevo flujo" flow — no new exports consumed by
  other tasks.

The row template's columns (`flow.execs`, `flow.err`, `flow.p95` if any
existed) must be checked against the current file — read
`flow-list.component.html` before editing; per Task 13 the `Flow` model no
longer has those fields, so the table's status/version/nodeCount/trigger
columns stay, and any execution-metric columns are replaced with a dash
placeholder (`—`) since that data belongs to the future execution-tracking
slice.

- [ ] **Step 1: Write the failing test**

Edit `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.spec.ts`.
Replace the existing `FLOW` fixture (find the object literal used across
the file's tests) with:

```ts
const FLOW = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: null,
  triggerSummary: 'CRON */5',
  activeVersionNumber: null,
  status: 'DRAFT',
  nodeCount: 0,
  archived: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
  version: 0,
};
```

Update every assertion in the existing tests that referenced the old
fields (`f.execs`, `f.err`, `f.p95`, `f.status` as `'PUBLISHED'` with an
`execCount24h` etc.) to match this fixture's shape — for example, the "lists
flow rows" test's `expect(text).toContain('PUBLISHED')` becomes
`expect(text).toContain('DRAFT')`.

Add this new test to the existing `describe('FlowListComponent', ...)` block:

```ts
  it('creates a flow and navigates to its designer', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    flush(fixture, []);

    (fixture.nativeElement.querySelector('.new-flow-btn') as HTMLButtonElement).click();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('input[name="code"]') as HTMLInputElement).value = 'flow/new';
    (fixture.nativeElement.querySelector('input[name="code"]') as HTMLInputElement).dispatchEvent(new Event('input'));
    (fixture.nativeElement.querySelector('input[name="name"]') as HTMLInputElement).value = 'New flow';
    (fixture.nativeElement.querySelector('input[name="name"]') as HTMLInputElement).dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { cancelable: true }),
    );

    const createRequest = http.expectOne('/bff/api/v1/flows');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual({ code: 'flow/new', name: 'New flow' });
    createRequest.flush(FLOW);

    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate');
    expect(navigateSpy).not.toThrow;
  });
```

Read the existing spec file's imports first — it already imports
`provideRouter`/`Router`/`TestBed` per the prior skeleton implementation;
reuse them, don't re-add duplicate imports.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --skip-nx-cache`
Expected: FAIL — `.new-flow-btn` doesn't exist (button is currently
`disabled` with no class), no create form is rendered.

- [ ] **Step 3: Write the minimal implementation**

Edit `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.ts`.
Replace the whole file:

```ts
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Flow, FlowStatus } from './flow.model';
import { FlowService } from './flow.service';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';
import { IntegrationTabsComponent } from '../shared/integration-tabs.component';

type FlowListState = 'loading' | 'ready' | 'empty' | 'unavailable';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-flow-list',
  standalone: true,
  imports: [ConsoleEmptyStateComponent, IntegrationTabsComponent],
  templateUrl: './flow-list.component.html',
  styleUrl: './flow-list.component.css',
})
export class FlowListComponent implements OnInit {
  private readonly flowService = inject(FlowService);
  private readonly router = inject(Router);

  readonly state = signal<FlowListState>('loading');
  readonly flows = signal<Flow[]>([]);
  readonly formOpen = signal(false);
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly createError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.flowService.list().subscribe({
      next: (flows) => {
        this.flows.set(flows);
        this.state.set(flows.length === 0 ? 'empty' : 'ready');
      },
      error: () => this.state.set('unavailable'),
    });
  }

  open(flow: Flow): void {
    this.router.navigate(['/integration/flows', flow.id]);
  }

  openExecutions(flow: Flow, event: Event): void {
    event.stopPropagation();
    this.router.navigate(['/integration/flows', flow.id, 'executions']);
  }

  statusBadgeClass(status: FlowStatus): string {
    return 'badge status-' + status.toLowerCase();
  }

  openForm(): void {
    this.formOpen.set(true);
    this.createError.set(null);
  }

  closeForm(): void {
    this.formOpen.set(false);
    this.newCode.set('');
    this.newName.set('');
  }

  onCodeInput(value: string): void {
    this.newCode.set(value);
  }

  onNameInput(value: string): void {
    this.newName.set(value);
  }

  submitCreate(event: Event): void {
    event.preventDefault();
    this.createError.set(null);
    this.flowService.create({ code: this.newCode(), name: this.newName() }).subscribe({
      next: (flow) => {
        this.closeForm();
        this.router.navigate(['/integration/flows', flow.id]);
      },
      error: () => this.createError.set('No se pudo crear el flujo. Verifica que el código no esté en uso.'),
    });
  }
}
```

Edit `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.html`.
Replace the `＋ Nuevo flujo` button and add the inline create form:

```html
<section class="page" aria-labelledby="flows-heading">
  <app-integration-tabs />
  <div class="page-header">
    <div>
      <h1 id="flows-heading">Flows</h1>
      <p>Un flujo es un grafo dirigido y versionado: fuentes, transformaciones, ramas y destinos conectados por pipes tipados.</p>
    </div>
    <button type="button" class="btn btn-primary new-flow-btn" (click)="openForm()">＋ Nuevo flujo</button>
  </div>

  @if (formOpen()) {
    <form class="card new-flow-form" (submit)="submitCreate($event)">
      <label>
        <span class="mono">CODE</span>
        <input name="code" [value]="newCode()" (input)="onCodeInput($any($event.target).value)" placeholder="flow/mi-flujo" required />
      </label>
      <label>
        <span class="mono">NAME</span>
        <input name="name" [value]="newName()" (input)="onNameInput($any($event.target).value)" placeholder="Mi flujo" required />
      </label>
      @if (createError(); as error) {
        <p role="alert">{{ error }}</p>
      }
      <div class="new-flow-actions">
        <button type="button" class="btn" (click)="closeForm()">Cancelar</button>
        <button type="submit" class="btn btn-primary">Crear</button>
      </div>
    </form>
  }

  @if (state() === 'loading') {
    <p class="state-message" aria-live="polite">Cargando flujos…</p>
  } @else if (state() === 'unavailable') {
    <app-console-empty-state
      title="Flows no disponible"
      description="El motor de flujos todavía no está implementado en el backend."
    >
      <button type="button" class="btn" (click)="load()">Reintentar</button>
    </app-console-empty-state>
  } @else if (state() === 'empty') {
    <app-console-empty-state title="Sin flujos" description="No hay flujos configurados todavía." />
  } @else {
    <div class="card">
      <table>
        <caption class="visually-hidden">Flujos configurados</caption>
        <thead>
          <tr>
            <th scope="col">Flujo</th>
            <th scope="col">Estado</th>
            <th scope="col">Versión activa</th>
            <th scope="col">Nodos</th>
            <th scope="col">Trigger</th>
            <th scope="col"></th>
          </tr>
        </thead>
        <tbody>
          @for (flow of flows(); track flow.id) {
            <tr (click)="open(flow)" class="row">
              <td class="cell-primary">
                {{ flow.name }}
                <div class="mono cell-sub">{{ flow.code }}</div>
              </td>
              <td><span [class]="statusBadgeClass(flow.status)">{{ flow.status }}</span></td>
              <td class="mono">{{ flow.activeVersionNumber !== null ? ('v' + flow.activeVersionNumber) : '—' }}</td>
              <td>{{ flow.nodeCount }} nodos</td>
              <td class="mono cell-sub">{{ flow.triggerSummary ?? '—' }}</td>
              <td>
                <button type="button" class="btn" (click)="openExecutions(flow, $event)">Ver traza</button>
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6">Ningún flujo coincide.</td></tr>
          }
        </tbody>
      </table>
    </div>
  }
</section>
```

Add this to `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.css`
(append, don't replace):

```css
.new-flow-form { padding: 16px; display: flex; flex-direction: column; gap: 10px; max-width: 420px; }
.new-flow-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; }
.new-flow-form input { border: 1px solid var(--border); border-radius: 5px; padding: 7px 10px; outline: none; }
.new-flow-form input:focus { border-color: var(--accent); }
.new-flow-actions { display: flex; justify-content: flex-end; gap: 8px; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --skip-nx-cache`
Expected: PASS — `flow-list.component.spec.ts` green (both the updated
existing tests and the new create-flow test).

- [ ] **Step 5: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/flow/flow-list.component.ts \
        backoffice/apps/integration-mfe/src/app/flow/flow-list.component.html \
        backoffice/apps/integration-mfe/src/app/flow/flow-list.component.css \
        backoffice/apps/integration-mfe/src/app/flow/flow-list.component.spec.ts
git commit -m "feat: enable creating a flow from the Flows list"
```

---

## Task 15: Functional flow designer (draft editor, publish, rollback)

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.css`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.spec.ts`

**Interfaces:**
- Consumes: `FlowService.get/updateDraft/listVersions/publish/rollback`
  (Task 13), `Flow`/`FlowVersion` (Task 13).
- Produces: nothing consumed elsewhere — this is a leaf component.

- [ ] **Step 1: Write the failing test**

Rewrite `backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.spec.ts`:

```ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { FlowDesignerComponent } from './flow-designer.component';

const FLOW_DRAFT = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: { nodes: [] },
  triggerSummary: 'CRON */5',
  activeVersionNumber: null,
  status: 'DRAFT',
  nodeCount: 0,
  archived: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
  version: 0,
};

const FLOW_VERSION = {
  id: 'v-1',
  flowId: 'f-1',
  versionNumber: 1,
  graph: { nodes: [] },
  state: 'ACTIVE',
  publishedBy: 'user@tenant',
  publishedAt: '2026-08-30T00:00:00Z',
};

function setup() {
  TestBed.configureTestingModule({
    imports: [FlowDesignerComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: { paramMap: new BehaviorSubject(convertToParamMap({ flowId: 'f-1' })).asObservable() },
      },
    ],
  });
  return {
    http: TestBed.inject(HttpTestingController),
    fixture: TestBed.createComponent(FlowDesignerComponent),
  };
}

describe('FlowDesignerComponent', () => {
  it('loads the flow and its version history', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Alta de vehiculos');
    http.verify();
  });

  it('saves draft changes', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.save-draft-btn') as HTMLButtonElement).click();

    const request = http.expectOne('/bff/api/v1/flows/f-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.expectedVersion).toBe(0);
    request.flush({ ...FLOW_DRAFT, version: 1 });
    http.verify();
  });

  it('publishes the draft and reloads the version history', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.publish-btn') as HTMLButtonElement).click();

    http.expectOne('/bff/api/v1/flows/f-1/versions/publish').flush(FLOW_VERSION);
    http.expectOne('/bff/api/v1/flows/f-1').flush({ ...FLOW_DRAFT, status: 'PUBLISHED', activeVersionNumber: 1 });
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([FLOW_VERSION]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('PUBLISHED');
    http.verify();
  });

  it('rolls back to a version listed in the history', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush({ ...FLOW_DRAFT, status: 'PUBLISHED', activeVersionNumber: 2 });
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([
      { ...FLOW_VERSION, id: 'v-2', versionNumber: 2, state: 'ACTIVE' },
      { ...FLOW_VERSION, id: 'v-1', versionNumber: 1, state: 'PUBLISHED' },
    ]);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.rollback-btn') as HTMLButtonElement).click();

    const request = http.expectOne('/bff/api/v1/flows/f-1/versions/1/rollback');
    expect(request.request.method).toBe('POST');
    request.flush({ ...FLOW_VERSION, versionNumber: 1, state: 'ACTIVE' });
    http.expectOne('/bff/api/v1/flows/f-1').flush({ ...FLOW_DRAFT, status: 'PUBLISHED', activeVersionNumber: 1 });
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    http.verify();
  });

  it('navigates back to the flows list', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate');
    (fixture.nativeElement.querySelector('.back-link') as HTMLButtonElement).click();
    expect(navigateSpy).toHaveBeenCalledWith(['/integration/flows']);
    http.verify();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backoffice && npx nx test integration-mfe --skip-nx-cache`
Expected: FAIL — the component doesn't request `/versions`, has no
`.save-draft-btn`/`.publish-btn`/`.rollback-btn`, and still shows the
placeholder canvas message.

- [ ] **Step 3: Write the minimal implementation**

Replace `backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.ts`:

```ts
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Flow, FlowVersion } from './flow.model';
import { FlowService } from './flow.service';
import { ConsoleEmptyStateComponent } from '../shared/console-empty-state.component';

type DesignerState = 'loading' | 'ready' | 'not-found' | 'unavailable';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-flow-designer',
  standalone: true,
  imports: [ConsoleEmptyStateComponent],
  templateUrl: './flow-designer.component.html',
  styleUrl: './flow-designer.component.css',
})
export class FlowDesignerComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly flowService = inject(FlowService);

  readonly state = signal<DesignerState>('loading');
  readonly flow = signal<Flow | null>(null);
  readonly versions = signal<FlowVersion[]>([]);
  readonly flowId = signal('');
  readonly nameDraft = signal('');
  readonly triggerDraft = signal('');
  readonly graphDraft = signal('');
  readonly saveError = signal<string | null>(null);
  readonly publishError = signal<string | null>(null);

  ngOnInit(): void {
    this.route.paramMap.subscribe((params) => {
      const id = params.get('flowId');
      if (!id) return;
      this.flowId.set(id);
      this.load(id);
    });
  }

  load(id: string): void {
    this.state.set('loading');
    this.flowService.get(id).subscribe({
      next: (flow) => {
        this.applyFlow(flow);
        this.flowService.listVersions(id).subscribe({
          next: (versions) => {
            this.versions.set(versions);
            this.state.set('ready');
          },
          error: () => this.state.set('unavailable'),
        });
      },
      error: (error: HttpErrorResponse) => this.state.set(error.status === 404 ? 'not-found' : 'unavailable'),
    });
  }

  retry(): void {
    this.load(this.flowId());
  }

  back(): void {
    this.router.navigate(['/integration/flows']);
  }

  openExecutions(): void {
    this.router.navigate(['/integration/flows', this.flowId(), 'executions']);
  }

  onNameInput(value: string): void {
    this.nameDraft.set(value);
  }

  onTriggerInput(value: string): void {
    this.triggerDraft.set(value);
  }

  onGraphInput(value: string): void {
    this.graphDraft.set(value);
  }

  saveDraft(): void {
    const current = this.flow();
    if (!current) return;
    this.saveError.set(null);
    let draftGraph: unknown = null;
    if (this.graphDraft().trim()) {
      try {
        draftGraph = JSON.parse(this.graphDraft());
      } catch {
        this.saveError.set('El grafo debe ser JSON válido.');
        return;
      }
    }
    this.flowService
      .updateDraft(this.flowId(), {
        name: this.nameDraft(),
        triggerSummary: this.triggerDraft() || null,
        draftGraph,
        expectedVersion: current.version,
      })
      .subscribe({
        next: (flow) => this.applyFlow(flow),
        error: () => this.saveError.set('No se pudo guardar el draft. Puede que otro usuario lo haya modificado.'),
      });
  }

  publish(): void {
    this.publishError.set(null);
    this.flowService.publish(this.flowId()).subscribe({
      next: () => this.load(this.flowId()),
      error: () => this.publishError.set('No se pudo publicar. El draft puede estar vacío.'),
    });
  }

  rollback(versionNumber: number): void {
    this.flowService.rollback(this.flowId(), versionNumber).subscribe({
      next: () => this.load(this.flowId()),
    });
  }

  private applyFlow(flow: Flow): void {
    this.flow.set(flow);
    this.nameDraft.set(flow.name);
    this.triggerDraft.set(flow.triggerSummary ?? '');
    this.graphDraft.set(flow.draftGraph ? JSON.stringify(flow.draftGraph, null, 2) : '');
  }
}
```

Replace `backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.html`:

```html
<section class="page" aria-labelledby="flow-designer-heading">
  <button type="button" class="btn-ghost back-link" (click)="back()">← Flows</button>

  @if (state() === 'loading') {
    <p class="state-message" aria-live="polite">Cargando flujo…</p>
  } @else if (state() === 'not-found') {
    <app-console-empty-state
      title="Flujo no encontrado"
      description="El flujo solicitado no existe o fue eliminado."
    />
  } @else if (state() === 'unavailable') {
    <app-console-empty-state
      title="Designer no disponible"
      description="El motor de flujos todavía no está implementado en el backend."
    >
      <button type="button" class="btn" (click)="retry()">Reintentar</button>
    </app-console-empty-state>
  } @else if (flow(); as f) {
    <div class="page-header">
      <div>
        <h1 id="flow-designer-heading">{{ f.name }}</h1>
        <p class="mono cell-sub">
          {{ f.code }} · {{ f.status }}
          @if (f.activeVersionNumber !== null) { · v{{ f.activeVersionNumber }} activa }
        </p>
      </div>
      <button type="button" class="btn" (click)="openExecutions()">Ver ejecuciones</button>
    </div>

    <div class="designer-grid">
      <div class="card draft-editor">
        <div class="card-header">Draft</div>
        <div class="draft-fields">
          <label>
            <span class="mono">NAME</span>
            <input [value]="nameDraft()" (input)="onNameInput($any($event.target).value)" />
          </label>
          <label>
            <span class="mono">TRIGGER</span>
            <input [value]="triggerDraft()" (input)="onTriggerInput($any($event.target).value)" placeholder="CRON */5" />
          </label>
          <label>
            <span class="mono">GRAPH (JSON)</span>
            <textarea [value]="graphDraft()" (input)="onGraphInput($any($event.target).value)" rows="12"></textarea>
          </label>
          @if (saveError(); as error) {
            <p role="alert">{{ error }}</p>
          }
          @if (publishError(); as error) {
            <p role="alert">{{ error }}</p>
          }
          <div class="draft-actions">
            <button type="button" class="btn save-draft-btn" (click)="saveDraft()">Guardar cambios</button>
            <button type="button" class="btn btn-primary publish-btn" (click)="publish()">Publicar</button>
          </div>
        </div>
      </div>

      <div class="card versions-panel">
        <div class="card-header">Historial de versiones</div>
        <div class="versions-list">
          @for (version of versions(); track version.id) {
            <div class="version-row">
              <span class="mono version-number">v{{ version.versionNumber }}</span>
              <span class="mono version-state">{{ version.state }}</span>
              <span class="version-by cell-sub">{{ version.publishedBy }}</span>
              @if (version.state !== 'ACTIVE') {
                <button type="button" class="btn rollback-btn" (click)="rollback(version.versionNumber)">Rollback</button>
              }
            </div>
          } @empty {
            <p class="cell-sub">Sin versiones publicadas todavía.</p>
          }
        </div>
      </div>
    </div>
  }
</section>
```

Append to `backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.css`
(the placeholder `.canvas-placeholder` rules can stay or be removed since
the template no longer uses them — remove them to avoid dead CSS):

```css
.state-message { padding: 24px 0; color: var(--text-muted); }
.back-link { padding: 8px 0 0 24px; align-self: flex-start; }
.cell-sub { color: var(--text-dim); font-size: 11px; }

.designer-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px; align-items: start; }
.draft-fields { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.draft-fields label { display: flex; flex-direction: column; gap: 5px; font-size: 12px; }
.draft-fields input, .draft-fields textarea {
  border: 1px solid var(--border); border-radius: 5px; padding: 7px 10px; outline: none;
  font-family: 'IBM Plex Mono', monospace; font-size: 12px;
}
.draft-fields input:focus, .draft-fields textarea:focus { border-color: var(--accent); }
.draft-actions { display: flex; justify-content: flex-end; gap: 8px; }

.versions-list { padding: 8px 16px 16px; display: flex; flex-direction: column; gap: 8px; }
.version-row { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--border-soft); }
.version-number { font-weight: 600; }
.version-by { margin-left: auto; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backoffice && npx nx test integration-mfe --skip-nx-cache`
Expected: PASS — all `flow-designer.component.spec.ts` tests green.

- [ ] **Step 5: Run the full backoffice suite and lint**

Run:
```bash
cd backoffice
npx nx test integration-mfe --skip-nx-cache
npx nx test shell --skip-nx-cache
npx nx test bff --skip-nx-cache
npx nx lint integration-mfe --skip-nx-cache
npx nx lint shell --skip-nx-cache
npx nx lint bff --skip-nx-cache
```
Expected: every command reports success (lint may show the same
pre-existing `no-explicit-any` warnings seen in earlier work — no new
errors).

- [ ] **Step 6: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.ts \
        backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.html \
        backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.css \
        backoffice/apps/integration-mfe/src/app/flow/flow-designer.component.spec.ts
git commit -m "feat: functional flow designer — draft editor, publish, rollback"
```

---

## Final verification

After Task 15, run the complete backend and frontend suites once more to
confirm the whole slice is green together:

```bash
cd application && mvn -q test
cd ../backoffice && npx nx run-many -t test -p integration-mfe,shell,bff --skip-nx-cache
```

Manually verify against the running stack (`docker compose up -d mysql
redis kafka app middleware backoffice-bff` from the repo root, `nx serve
shell`/`nx serve integration-mfe`, or `http://localhost:4000` if the BFF
container was rebuilt with `--build`): create a flow, edit its draft graph,
publish it, publish a second version, and roll back to the first — the
version history panel should reflect `ACTIVE`/`PUBLISHED`/`ROLLED_BACK`
correctly at each step.
