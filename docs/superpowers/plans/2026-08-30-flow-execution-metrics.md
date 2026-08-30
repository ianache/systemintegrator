# Flow Execution Tracking and Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `flow_execution` record + tenant-scoped metrics summary so the Flows tab in the backoffice can show real "Flujos publicados / Ejecuciones 24h / Tasa de error / P95 por ejecución" KPI cards.

**Architecture:** Same hexagonal slice pattern as `Flow`/`FlowVersion` (`application/src/main/java/com/cl2/integration/{domain,application,adapter}`): an immutable, insert-only `FlowExecution` domain object, a `FlowExecutionRepository` port, a JPA adapter that also computes the p95/error-rate/count aggregates via SQL, a `FlowMetricsService` application service, two new `FlowController` endpoints, and matching BFF proxy + Angular frontend changes to render the KPI cards on `flow-list.component`.

**Tech Stack:** Spring Boot (Java), MySQL 8.4 + Flyway, Angular (standalone components, signals), NestJS BFF.

**Spec:** `docs/superpowers/specs/2026-08-30-flow-execution-metrics-design.md`

## Global Constraints

- Tenant scoping always via `TenantContext.requireTenantId()` on the backend — never trust a tenant id from the request body/path.
- `FlowExecution` is insert-only: no update/delete methods, no optimistic-locking `version` column (unlike `Flow`/`FlowVersion`).
- p95 is computed with two plain SQL queries (`COUNT` + `ORDER BY duration_ms ASC LIMIT 1 OFFSET ceil(n*0.95)-1`), not `PERCENTILE_CONT` — MySQL 8.4 support for it is not guaranteed in this project's setup.
- `errorRatePct` is `0.0` (not `null`/NaN) when there are zero executions in the 24h window; `p95DurationMs` is `null` when there are zero executions.
- New Flyway migration continues the numbering: `V13__create_flow_execution.sql` (`V12` is the last existing one).
- Backend tests that touch persistence need MySQL running: `docker compose up -d mysql` before `mvn test` (see `README.md`).

---

## File Structure

**Backend (`application/src/main/java/com/cl2/integration/`):**

- `domain/model/FlowExecutionStatus.java` — new enum `SUCCESS`, `FAILURE`.
- `domain/model/FlowExecution.java` — new immutable domain object.
- `domain/port/FlowExecutionRepository.java` — new port: `save`, `summarize`.
- `application/FlowMetricsSummary.java` — new record (application-layer view, not persisted).
- `application/exception/FlowExecutionInvalidException.java` — new exception (422).
- `application/command/ReportFlowExecutionCommand.java` — new command record.
- `application/FlowMetricsService.java` — new service: `report(...)`, `summarize(...)`.
- `adapter/out/persistence/FlowExecutionJpaEntity.java` — new JPA entity.
- `adapter/out/persistence/SpringDataFlowExecutionRepository.java` — new Spring Data repo with native queries for the aggregates.
- `adapter/out/persistence/FlowExecutionPersistenceAdapter.java` — new adapter implementing the port.
- `adapter/in/web/dto/ReportFlowExecutionRequest.java` — new request DTO.
- `adapter/in/web/dto/FlowExecutionResponse.java` — new response DTO.
- `adapter/in/web/dto/FlowMetricsSummaryResponse.java` — new response DTO.
- `adapter/in/web/FlowController.java` — **modify**: add `POST /{flowId}/executions` and `GET /metrics/summary`.
- `adapter/in/web/ApiExceptionHandler.java` — **modify**: map `FlowExecutionInvalidException` → `422`.
- `src/main/resources/db/migration/V13__create_flow_execution.sql` — new migration.

**Backend tests:**

- `domain/model/FlowExecutionTest.java` — new.
- `application/FlowMetricsServiceTest.java` — new.
- `adapter/out/persistence/FlowExecutionPersistenceAdapterTest.java` — new.
- `adapter/in/web/FlowControllerTest.java` — **modify**: add cases for the two new endpoints.

**BFF (`backoffice/apps/bff/src/gateway-proxy/`):**

- `gateway-proxy.service.ts` — **modify**: add `reportFlowExecution`, `getFlowMetricsSummary`.
- `gateway-proxy.controller.ts` — **modify**: add matching `POST`/`GET` routes.

**Frontend (`backoffice/apps/integration-mfe/src/app/flow/`):**

- `flow.model.ts` — **modify**: add `FlowExecutionStatus`, `FlowMetricsSummary`, `ReportFlowExecutionPayload`.
- `flow.service.ts` — **modify**: add `getMetricsSummary()`, `reportExecution()`.
- `flow.service.spec.ts` — **modify**: add tests for the two new methods.
- `flow-list.component.ts` — **modify**: load metrics summary alongside flows, expose signals for the template.
- `flow-list.component.html` — **modify**: add the `.kpi-grid` block.
- `flow-list.component.css` — **modify**: add `.kpi-grid`/`.kpi` styles (duplicated from `dashboard-page.component.css` — no shared abstraction yet, per spec).
- `flow-list.component.spec.ts` — **modify**: add tests covering the KPI cards (happy path + metrics-unavailable path).

---

### Task 1: Domain model — `FlowExecution`

**Files:**
- Create: `application/src/main/java/com/cl2/integration/domain/model/FlowExecutionStatus.java`
- Create: `application/src/main/java/com/cl2/integration/domain/model/FlowExecution.java`
- Test: `application/src/test/java/com/cl2/integration/domain/model/FlowExecutionTest.java`

**Interfaces:**
- Produces: `FlowExecution.report(UUID id, UUID tenantId, UUID flowId, int flowVersionNumber, FlowExecutionStatus status, Instant startedAt, Instant finishedAt, String errorMessage)` — static factory, throws `IllegalArgumentException` if `finishedAt` is before `startedAt`. Produces `FlowExecution.rehydrate(...)` (same params plus `UUID id` already included, used by the persistence adapter to reconstruct from a row) and getters: `id()`, `tenantId()`, `flowId()`, `flowVersionNumber()`, `status()`, `startedAt()`, `finishedAt()`, `durationMs()`, `errorMessage()`.

- [ ] **Step 1: Write the failing tests**

```java
package com.cl2.integration.domain.model;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowExecutionTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID FLOW_ID = UUID.randomUUID();

    @Test
    void computesDurationFromStartAndFinish() {
        Instant started = Instant.parse("2026-08-30T00:00:00Z");
        Instant finished = Instant.parse("2026-08-30T00:00:00.750Z");

        FlowExecution execution = FlowExecution.report(UUID.randomUUID(), TENANT_ID, FLOW_ID, 1,
                FlowExecutionStatus.SUCCESS, started, finished, null);

        assertThat(execution.durationMs()).isEqualTo(750);
        assertThat(execution.status()).isEqualTo(FlowExecutionStatus.SUCCESS);
        assertThat(execution.errorMessage()).isNull();
    }

    @Test
    void rejectsAFinishTimeBeforeTheStartTime() {
        Instant started = Instant.parse("2026-08-30T00:00:01Z");
        Instant finished = Instant.parse("2026-08-30T00:00:00Z");

        assertThatThrownBy(() -> FlowExecution.report(UUID.randomUUID(), TENANT_ID, FLOW_ID, 1,
                FlowExecutionStatus.FAILURE, started, finished, "boom"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsTheErrorMessageForAFailure() {
        Instant started = Instant.parse("2026-08-30T00:00:00Z");
        Instant finished = Instant.parse("2026-08-30T00:00:01Z");

        FlowExecution execution = FlowExecution.report(UUID.randomUUID(), TENANT_ID, FLOW_ID, 2,
                FlowExecutionStatus.FAILURE, started, finished, "connector timeout");

        assertThat(execution.errorMessage()).isEqualTo("connector timeout");
        assertThat(execution.flowVersionNumber()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl application -am test -Dtest=FlowExecutionTest`
Expected: FAIL to compile — `FlowExecution` and `FlowExecutionStatus` do not exist yet.

- [ ] **Step 3: Create `FlowExecutionStatus`**

```java
package com.cl2.integration.domain.model;

public enum FlowExecutionStatus {
    SUCCESS,
    FAILURE
}
```

- [ ] **Step 4: Create `FlowExecution`**

```java
package com.cl2.integration.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class FlowExecution {

    private final UUID id;
    private final UUID tenantId;
    private final UUID flowId;
    private final int flowVersionNumber;
    private final FlowExecutionStatus status;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final long durationMs;
    private final String errorMessage;

    private FlowExecution(UUID id, UUID tenantId, UUID flowId, int flowVersionNumber, FlowExecutionStatus status,
                           Instant startedAt, Instant finishedAt, String errorMessage) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.flowVersionNumber = flowVersionNumber;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not be before startedAt");
        }
        this.durationMs = Duration.between(startedAt, finishedAt).toMillis();
        this.errorMessage = errorMessage;
    }

    public static FlowExecution report(UUID id, UUID tenantId, UUID flowId, int flowVersionNumber,
                                        FlowExecutionStatus status, Instant startedAt, Instant finishedAt,
                                        String errorMessage) {
        return new FlowExecution(id, tenantId, flowId, flowVersionNumber, status, startedAt, finishedAt, errorMessage);
    }

    public static FlowExecution rehydrate(UUID id, UUID tenantId, UUID flowId, int flowVersionNumber,
                                           FlowExecutionStatus status, Instant startedAt, Instant finishedAt,
                                           String errorMessage) {
        return new FlowExecution(id, tenantId, flowId, flowVersionNumber, status, startedAt, finishedAt, errorMessage);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID flowId() {
        return flowId;
    }

    public int flowVersionNumber() {
        return flowVersionNumber;
    }

    public FlowExecutionStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public long durationMs() {
        return durationMs;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl application -am test -Dtest=FlowExecutionTest`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add application/src/main/java/com/cl2/integration/domain/model/FlowExecution.java application/src/main/java/com/cl2/integration/domain/model/FlowExecutionStatus.java application/src/test/java/com/cl2/integration/domain/model/FlowExecutionTest.java
git commit -m "feat: add FlowExecution domain model"
```

---

### Task 2: Migration + JPA entity + Spring Data repository

**Files:**
- Create: `application/src/main/resources/db/migration/V13__create_flow_execution.sql`
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowExecutionJpaEntity.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataFlowExecutionRepository.java`

**Interfaces:**
- Consumes: `FlowExecution` from Task 1 (`FlowExecution.rehydrate(...)`, getters).
- Produces: `FlowExecutionJpaEntity.from(FlowExecution)` (static), `.toDomain()` (instance); `SpringDataFlowExecutionRepository` methods: `countByTenantIdAndStartedAtGreaterThanEqual(UUID tenantId, Instant since)`, `countByTenantIdAndStartedAtGreaterThanEqualAndStatus(UUID tenantId, Instant since, FlowExecutionStatus status)`, `findDurationAtOffset(UUID tenantId, Instant since, int offset)` (native query returning `Long`, nullable).

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE flow_execution (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    flow_id BINARY(16) NOT NULL,
    flow_version_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NOT NULL,
    duration_ms BIGINT NOT NULL,
    error_message TEXT NULL,
    PRIMARY KEY (id),
    KEY idx_flow_execution_tenant_started (tenant_id, started_at, duration_ms),
    CONSTRAINT fk_flow_execution_flow FOREIGN KEY (flow_id) REFERENCES flow (id)
);
```

Save as `application/src/main/resources/db/migration/V13__create_flow_execution.sql`.

- [ ] **Step 2: Write the JPA entity**

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStatus;
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
@Table(name = "flow_execution")
class FlowExecutionJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "flow_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID flowId;

    @Column(name = "flow_version_number", nullable = false)
    private int flowVersionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FlowExecutionStatus status;

    @Column(name = "started_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant finishedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    protected FlowExecutionJpaEntity() {
    }

    private FlowExecutionJpaEntity(FlowExecution execution) {
        this.id = execution.id();
        this.tenantId = execution.tenantId();
        this.flowId = execution.flowId();
        this.flowVersionNumber = execution.flowVersionNumber();
        this.status = execution.status();
        this.startedAt = toMysqlTimestamp(execution.startedAt());
        this.finishedAt = toMysqlTimestamp(execution.finishedAt());
        this.durationMs = execution.durationMs();
        this.errorMessage = execution.errorMessage();
    }

    static FlowExecutionJpaEntity from(FlowExecution execution) {
        return new FlowExecutionJpaEntity(execution);
    }

    FlowExecution toDomain() {
        return FlowExecution.rehydrate(id, tenantId, flowId, flowVersionNumber, status, startedAt, finishedAt,
                errorMessage);
    }

    private static Instant toMysqlTimestamp(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }
}
```

- [ ] **Step 3: Write the Spring Data repository**

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface SpringDataFlowExecutionRepository extends Repository<FlowExecutionJpaEntity, UUID> {

    FlowExecutionJpaEntity save(FlowExecutionJpaEntity entity);

    long countByTenantIdAndStartedAtGreaterThanEqual(UUID tenantId, Instant since);

    long countByTenantIdAndStartedAtGreaterThanEqualAndStatus(UUID tenantId, Instant since, FlowExecutionStatus status);

    @Query(value = """
            select duration_ms from flow_execution
            where tenant_id = :tenantId and started_at >= :since
            order by duration_ms asc
            limit 1 offset :offset
            """, nativeQuery = true)
    Long findDurationAtOffset(@Param("tenantId") UUID tenantId, @Param("since") Instant since, @Param("offset") int offset);
}
```

Note: `tenantId` is `BINARY(16)` in the schema — Hibernate/the MySQL driver already handles `UUID` ↔ `BINARY(16)` transparently for derived queries and `@Param` bindings elsewhere in this codebase (see `SpringDataFlowRepository`), so no extra converter is needed here.

- [ ] **Step 4: Compile**

Run: `mvn -pl application -am compile`
Expected: BUILD SUCCESS (no tests yet reference these classes beyond compilation)

- [ ] **Step 5: Commit**

```bash
git add application/src/main/resources/db/migration/V13__create_flow_execution.sql application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowExecutionJpaEntity.java application/src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataFlowExecutionRepository.java
git commit -m "feat: add flow_execution table, JPA entity and Spring Data repository"
```

---

### Task 3: `FlowExecutionRepository` port + persistence adapter (with p95 calculation)

**Files:**
- Create: `application/src/main/java/com/cl2/integration/domain/port/FlowExecutionRepository.java`
- Create: `application/src/main/java/com/cl2/integration/application/FlowMetricsSummary.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowExecutionPersistenceAdapter.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/out/persistence/FlowExecutionPersistenceAdapterTest.java`

**Interfaces:**
- Consumes: `FlowExecution` (Task 1), `FlowExecutionJpaEntity`/`SpringDataFlowExecutionRepository` (Task 2), `FlowRepository.findById` (existing, used only for the 404 check — actually performed in Task 4's service, not here) — this task only touches execution data, not `Flow`.
- Produces: `FlowExecutionRepository.save(UUID tenantId, FlowExecution execution)`, `FlowExecutionRepository.executionMetrics(UUID tenantId, Instant since)` returning `FlowMetricsSummary` fields `executions24h` (long), `errorRatePct` (double), `p95DurationMs` (Long, nullable) — **not** `publishedFlowCount`, which Task 4's service fills in separately from `FlowRepository`. `FlowMetricsSummary` record: `(long executions24h, double errorRatePct, Long p95DurationMs, long publishedFlowCount)` with `publishedFlowCount` defaulted to `0` when constructed by this adapter (Task 4 overwrites it).

- [ ] **Step 1: Write the port**

```java
package com.cl2.integration.domain.port;

import com.cl2.integration.application.FlowMetricsSummary;
import com.cl2.integration.domain.model.FlowExecution;
import java.time.Instant;
import java.util.UUID;

public interface FlowExecutionRepository {

    FlowExecution save(UUID tenantId, FlowExecution execution);

    FlowMetricsSummary executionMetrics(UUID tenantId, Instant since);
}
```

- [ ] **Step 2: Write `FlowMetricsSummary`**

```java
package com.cl2.integration.application;

public record FlowMetricsSummary(
        long publishedFlowCount,
        long executions24h,
        double errorRatePct,
        Long p95DurationMs) {

    public FlowMetricsSummary withPublishedFlowCount(long publishedFlowCount) {
        return new FlowMetricsSummary(publishedFlowCount, executions24h, errorRatePct, p95DurationMs);
    }
}
```

- [ ] **Step 3: Write the failing persistence test**

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.FlowMetricsSummary;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class FlowExecutionPersistenceAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");

    @Autowired
    private FlowExecutionPersistenceAdapter adapter;

    @Autowired
    private FlowPersistenceAdapter flowAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID flowId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM flow_execution");
        jdbcTemplate.update("DELETE FROM flow_version");
        jdbcTemplate.update("DELETE FROM flow");
        flowId = flowAdapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X")).id();
    }

    @Test
    void savesAndReadsBackAnExecution() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.MICROS);
        FlowExecution execution = FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, started, started.plusMillis(500), null);

        FlowExecution saved = adapter.save(TENANT_ID, execution);

        assertThat(saved.durationMs()).isEqualTo(500);
    }

    @Test
    void countsOnlyExecutionsWithinTheWindowAndForTheTenant() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant withinWindow = now.minus(1, ChronoUnit.HOURS);
        Instant outsideWindow = now.minus(25, ChronoUnit.HOURS);

        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, withinWindow, withinWindow.plusMillis(100), null));
        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, outsideWindow, outsideWindow.plusMillis(100), null));
        adapter.save(OTHER_TENANT_ID, FlowExecution.report(UUID.randomUUID(), OTHER_TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, withinWindow, withinWindow.plusMillis(100), null));

        FlowMetricsSummary summary = adapter.executionMetrics(TENANT_ID, now.minus(24, ChronoUnit.HOURS));

        assertThat(summary.executions24h()).isEqualTo(1);
    }

    @Test
    void computesErrorRateAsZeroWhenThereAreNoExecutions() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        FlowMetricsSummary summary = adapter.executionMetrics(TENANT_ID, now.minus(24, ChronoUnit.HOURS));

        assertThat(summary.executions24h()).isZero();
        assertThat(summary.errorRatePct()).isEqualTo(0.0);
        assertThat(summary.p95DurationMs()).isNull();
    }

    @Test
    void computesErrorRateWithMixedResults() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t = now.minus(1, ChronoUnit.HOURS);

        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, t, t.plusMillis(100), null));
        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, t, t.plusMillis(100), null));
        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, t, t.plusMillis(100), null));
        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.FAILURE, t, t.plusMillis(100), "boom"));

        FlowMetricsSummary summary = adapter.executionMetrics(TENANT_ID, now.minus(24, ChronoUnit.HOURS));

        assertThat(summary.executions24h()).isEqualTo(4);
        assertThat(summary.errorRatePct()).isEqualTo(25.0);
    }

    @Test
    void computesP95OverTwentyKnownDurations() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t = now.minus(1, ChronoUnit.HOURS);

        // Durations 100ms..2000ms in steps of 100ms (20 samples).
        // ceil(20 * 0.95) - 1 = 18 -> 0-indexed 19th smallest value = 1900ms.
        for (int i = 1; i <= 20; i++) {
            long durationMs = i * 100L;
            adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                    FlowExecutionStatus.SUCCESS, t, t.plusMillis(durationMs), null));
        }

        FlowMetricsSummary summary = adapter.executionMetrics(TENANT_ID, now.minus(24, ChronoUnit.HOURS));

        assertThat(summary.p95DurationMs()).isEqualTo(1900L);
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `docker compose up -d mysql && mvn -pl application -am test -Dtest=FlowExecutionPersistenceAdapterTest`
Expected: FAIL to compile — `FlowExecutionPersistenceAdapter` does not exist yet.

- [ ] **Step 5: Write the persistence adapter**

```java
package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.FlowMetricsSummary;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStatus;
import com.cl2.integration.domain.port.FlowExecutionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class FlowExecutionPersistenceAdapter implements FlowExecutionRepository {

    private final SpringDataFlowExecutionRepository repository;
    private final EntityManager entityManager;

    FlowExecutionPersistenceAdapter(SpringDataFlowExecutionRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public FlowExecution save(UUID tenantId, FlowExecution execution) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(execution, "execution must not be null");
        if (!tenantId.equals(execution.tenantId())) {
            throw new IllegalArgumentException("tenantId must match the execution tenantId");
        }
        FlowExecutionJpaEntity entity = FlowExecutionJpaEntity.from(execution);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public FlowMetricsSummary executionMetrics(UUID tenantId, Instant since) {
        long total = repository.countByTenantIdAndStartedAtGreaterThanEqual(tenantId, since);
        if (total == 0) {
            return new FlowMetricsSummary(0, 0, 0.0, null);
        }
        long failures = repository.countByTenantIdAndStartedAtGreaterThanEqualAndStatus(
                tenantId, since, FlowExecutionStatus.FAILURE);
        double errorRatePct = 100.0 * failures / total;

        int offset = (int) Math.min(Math.ceil(total * 0.95) - 1, total - 1);
        Long p95 = repository.findDurationAtOffset(tenantId, since, offset);

        return new FlowMetricsSummary(0, total, errorRatePct, p95);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl application -am test -Dtest=FlowExecutionPersistenceAdapterTest`
Expected: PASS (5 tests)

- [ ] **Step 7: Commit**

```bash
git add application/src/main/java/com/cl2/integration/domain/port/FlowExecutionRepository.java application/src/main/java/com/cl2/integration/application/FlowMetricsSummary.java application/src/main/java/com/cl2/integration/adapter/out/persistence/FlowExecutionPersistenceAdapter.java application/src/test/java/com/cl2/integration/adapter/out/persistence/FlowExecutionPersistenceAdapterTest.java
git commit -m "feat: add FlowExecutionRepository port and persistence adapter with p95 calculation"
```

---

### Task 4: `FlowMetricsService` (application layer)

**Files:**
- Create: `application/src/main/java/com/cl2/integration/application/exception/FlowExecutionInvalidException.java`
- Create: `application/src/main/java/com/cl2/integration/application/command/ReportFlowExecutionCommand.java`
- Create: `application/src/main/java/com/cl2/integration/application/FlowMetricsService.java`
- Test: `application/src/test/java/com/cl2/integration/application/FlowMetricsServiceTest.java`

**Interfaces:**
- Consumes: `FlowRepository` (existing, for `findById` 404-check and `findAll` to compute `publishedFlowCount`), `FlowExecutionRepository` (Task 3: `save`, `executionMetrics`), `FlowExecution.report(...)` (Task 1), `FlowMetricsSummary` (Task 3).
- Produces: `FlowMetricsService.report(UUID tenantId, UUID flowId, ReportFlowExecutionCommand command)` returning `FlowExecution`; `FlowMetricsService.summarize(UUID tenantId)` returning `FlowMetricsSummary` (with `publishedFlowCount` correctly filled in). `ReportFlowExecutionCommand(int flowVersionNumber, FlowExecutionStatus status, Instant startedAt, Instant finishedAt, String errorMessage)`.

- [ ] **Step 1: Write `FlowExecutionInvalidException`**

```java
package com.cl2.integration.application.exception;

public class FlowExecutionInvalidException extends RuntimeException {

    public FlowExecutionInvalidException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Write `ReportFlowExecutionCommand`**

```java
package com.cl2.integration.application.command;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;

public record ReportFlowExecutionCommand(
        int flowVersionNumber,
        FlowExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage) {
}
```

- [ ] **Step 3: Write the failing service test**

```java
package com.cl2.integration.application;

import com.cl2.integration.application.command.ReportFlowExecutionCommand;
import com.cl2.integration.application.exception.FlowExecutionInvalidException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStatus;
import com.cl2.integration.domain.model.FlowStatus;
import com.cl2.integration.domain.port.FlowExecutionRepository;
import com.cl2.integration.domain.port.FlowRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

class FlowMetricsServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID FLOW_ID = UUID.randomUUID();

    @Mock
    private FlowRepository flowRepository;

    @Mock
    private FlowExecutionRepository flowExecutionRepository;

    private FlowMetricsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new FlowMetricsService(flowRepository, flowExecutionRepository);
    }

    @Test
    void reportsAnExecutionForAnExistingFlow() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");
        given(flowRepository.findById(TENANT_ID, FLOW_ID)).willReturn(flow);
        given(flowExecutionRepository.save(eq(TENANT_ID), any(FlowExecution.class)))
                .willAnswer(invocation -> invocation.getArgument(1));

        Instant started = Instant.parse("2026-08-30T00:00:00Z");
        Instant finished = Instant.parse("2026-08-30T00:00:01Z");
        ReportFlowExecutionCommand command = new ReportFlowExecutionCommand(1, FlowExecutionStatus.SUCCESS,
                started, finished, null);

        FlowExecution result = service.report(TENANT_ID, FLOW_ID, command);

        assertThat(result.flowId()).isEqualTo(FLOW_ID);
        assertThat(result.durationMs()).isEqualTo(1000);
        then(flowExecutionRepository).should().save(eq(TENANT_ID), any(FlowExecution.class));
    }

    @Test
    void rejectsAFinishTimeBeforeTheStartTime() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");
        given(flowRepository.findById(TENANT_ID, FLOW_ID)).willReturn(flow);

        Instant started = Instant.parse("2026-08-30T00:00:01Z");
        Instant finished = Instant.parse("2026-08-30T00:00:00Z");
        ReportFlowExecutionCommand command = new ReportFlowExecutionCommand(1, FlowExecutionStatus.FAILURE,
                started, finished, "boom");

        assertThatThrownBy(() -> service.report(TENANT_ID, FLOW_ID, command))
                .isInstanceOf(FlowExecutionInvalidException.class);
    }

    @Test
    void summarizeFillsInThePublishedFlowCountFromTheFlowRepository() {
        Flow published = Flow.create(UUID.randomUUID(), TENANT_ID, "flow/a", "A").withActiveVersion(1);
        Flow draft = Flow.create(UUID.randomUUID(), TENANT_ID, "flow/b", "B");
        given(flowRepository.findAll(TENANT_ID, true)).willReturn(List.of(published, draft));
        given(flowExecutionRepository.executionMetrics(eq(TENANT_ID), any(Instant.class)))
                .willReturn(new FlowMetricsSummary(0, 10, 5.0, 200L));

        FlowMetricsSummary summary = service.summarize(TENANT_ID);

        assertThat(summary.publishedFlowCount()).isEqualTo(1);
        assertThat(summary.executions24h()).isEqualTo(10);
        assertThat(summary.errorRatePct()).isEqualTo(5.0);
        assertThat(summary.p95DurationMs()).isEqualTo(200L);
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn -pl application -am test -Dtest=FlowMetricsServiceTest`
Expected: FAIL to compile — `FlowMetricsService` does not exist yet.

- [ ] **Step 5: Write `FlowMetricsService`**

```java
package com.cl2.integration.application;

import com.cl2.integration.application.command.ReportFlowExecutionCommand;
import com.cl2.integration.application.exception.FlowExecutionInvalidException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.port.FlowExecutionRepository;
import com.cl2.integration.domain.port.FlowRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowMetricsService {

    private final FlowRepository flowRepository;
    private final FlowExecutionRepository flowExecutionRepository;

    public FlowMetricsService(FlowRepository flowRepository, FlowExecutionRepository flowExecutionRepository) {
        this.flowRepository = flowRepository;
        this.flowExecutionRepository = flowExecutionRepository;
    }

    @Transactional
    public FlowExecution report(UUID tenantId, UUID flowId, ReportFlowExecutionCommand command) {
        Flow flow = flowRepository.findById(tenantId, flowId);
        try {
            FlowExecution execution = FlowExecution.report(UUID.randomUUID(), tenantId, flow.id(),
                    command.flowVersionNumber(), command.status(), command.startedAt(), command.finishedAt(),
                    command.errorMessage());
            return flowExecutionRepository.save(tenantId, execution);
        } catch (IllegalArgumentException e) {
            throw new FlowExecutionInvalidException(e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public FlowMetricsSummary summarize(UUID tenantId) {
        long publishedFlowCount = flowRepository.findAll(tenantId, true).stream()
                .filter(flow -> flow.activeVersionNumber() != null)
                .count();
        Instant since = Instant.now().minus(Duration.ofHours(24));
        return flowExecutionRepository.executionMetrics(tenantId, since).withPublishedFlowCount(publishedFlowCount);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl application -am test -Dtest=FlowMetricsServiceTest`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
git add application/src/main/java/com/cl2/integration/application/exception/FlowExecutionInvalidException.java application/src/main/java/com/cl2/integration/application/command/ReportFlowExecutionCommand.java application/src/main/java/com/cl2/integration/application/FlowMetricsService.java application/src/test/java/com/cl2/integration/application/FlowMetricsServiceTest.java
git commit -m "feat: add FlowMetricsService"
```

---

### Task 5: REST endpoints on `FlowController`

**Files:**
- Create: `application/src/main/java/com/cl2/integration/adapter/in/web/dto/ReportFlowExecutionRequest.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowExecutionResponse.java`
- Create: `application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowMetricsSummaryResponse.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/in/web/FlowController.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java`
- Modify: `application/src/test/java/com/cl2/integration/adapter/in/web/FlowControllerTest.java`

**Interfaces:**
- Consumes: `FlowMetricsService` (Task 4: `report`, `summarize`), `ReportFlowExecutionCommand` (Task 4), `FlowMetricsSummary` (Task 3), `FlowExecution` (Task 1).
- Produces: `POST /api/v1/flows/{flowId}/executions` → `201` `FlowExecutionResponse`; `GET /api/v1/flows/metrics/summary` → `200` `FlowMetricsSummaryResponse`.

- [ ] **Step 1: Write the request/response DTOs**

```java
package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.FlowExecutionStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ReportFlowExecutionRequest(
        @NotNull Integer flowVersionNumber,
        @NotNull FlowExecutionStatus status,
        @NotNull Instant startedAt,
        @NotNull Instant finishedAt,
        String errorMessage) {
}
```

```java
package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.domain.model.FlowExecution;
import java.time.Instant;
import java.util.UUID;

public record FlowExecutionResponse(
        UUID id,
        UUID flowId,
        int flowVersionNumber,
        String status,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String errorMessage) {

    public static FlowExecutionResponse from(FlowExecution execution) {
        return new FlowExecutionResponse(execution.id(), execution.flowId(), execution.flowVersionNumber(),
                execution.status().name(), execution.startedAt(), execution.finishedAt(), execution.durationMs(),
                execution.errorMessage());
    }
}
```

```java
package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.FlowMetricsSummary;

public record FlowMetricsSummaryResponse(
        long publishedFlowCount,
        long executions24h,
        double errorRatePct,
        Long p95DurationMs) {

    public static FlowMetricsSummaryResponse from(FlowMetricsSummary summary) {
        return new FlowMetricsSummaryResponse(summary.publishedFlowCount(), summary.executions24h(),
                summary.errorRatePct(), summary.p95DurationMs());
    }
}
```

- [ ] **Step 2: Write the failing controller tests**

Add to `application/src/test/java/com/cl2/integration/adapter/in/web/FlowControllerTest.java` (new imports: `FlowExecution`, `FlowExecutionStatus`, `FlowMetricsService`, `FlowMetricsSummary`, `ReportFlowExecutionCommand`, `FlowExecutionInvalidException`; new `@MockitoBean private FlowMetricsService metricsService;`):

```java
    @Test
    void reportsAnExecutionForAFlow() throws Exception {
        FlowExecution execution = FlowExecution.report(UUID.randomUUID(), TENANT_ID, FLOW_ID, 1,
                FlowExecutionStatus.SUCCESS, Instant.parse("2026-08-30T00:00:00Z"),
                Instant.parse("2026-08-30T00:00:01Z"), null);
        given(metricsService.report(eq(TENANT_ID), eq(FLOW_ID), any(ReportFlowExecutionCommand.class)))
                .willReturn(execution);

        mockMvc.perform(post(BASE_PATH + "/{flowId}/executions", FLOW_ID)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flowVersionNumber":1,"status":"SUCCESS","startedAt":"2026-08-30T00:00:00Z","finishedAt":"2026-08-30T00:00:01Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.durationMs").value(1000));
    }

    @Test
    void returns404WhenReportingAnExecutionForAMissingFlow() throws Exception {
        given(metricsService.report(eq(TENANT_ID), eq(FLOW_ID), any(ReportFlowExecutionCommand.class)))
                .willThrow(new FlowNotFoundException("not found"));

        mockMvc.perform(post(BASE_PATH + "/{flowId}/executions", FLOW_ID)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flowVersionNumber":1,"status":"SUCCESS","startedAt":"2026-08-30T00:00:00Z","finishedAt":"2026-08-30T00:00:01Z"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns422WhenFinishedAtIsBeforeStartedAt() throws Exception {
        given(metricsService.report(eq(TENANT_ID), eq(FLOW_ID), any(ReportFlowExecutionCommand.class)))
                .willThrow(new FlowExecutionInvalidException("finishedAt must not be before startedAt"));

        mockMvc.perform(post(BASE_PATH + "/{flowId}/executions", FLOW_ID)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flowVersionNumber":1,"status":"SUCCESS","startedAt":"2026-08-30T00:00:01Z","finishedAt":"2026-08-30T00:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void returnsTheMetricsSummaryForTheTenant() throws Exception {
        given(metricsService.summarize(TENANT_ID)).willReturn(new FlowMetricsSummary(3, 40, 2.5, 810L));

        mockMvc.perform(get(BASE_PATH + "/metrics/summary").header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedFlowCount").value(3))
                .andExpect(jsonPath("$.executions24h").value(40))
                .andExpect(jsonPath("$.errorRatePct").value(2.5))
                .andExpect(jsonPath("$.p95DurationMs").value(810));
    }

    @Test
    void returnsZeroExecutionsAndNullP95WhenTenantHasNoExecutions() throws Exception {
        given(metricsService.summarize(TENANT_ID)).willReturn(new FlowMetricsSummary(0, 0, 0.0, null));

        mockMvc.perform(get(BASE_PATH + "/metrics/summary").header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executions24h").value(0))
                .andExpect(jsonPath("$.errorRatePct").value(0.0))
                .andExpect(jsonPath("$.p95DurationMs").doesNotExist());
    }
```

Add the corresponding imports at the top of the file (`com.cl2.integration.application.FlowMetricsService`,
`com.cl2.integration.application.FlowMetricsSummary`, `com.cl2.integration.application.command.ReportFlowExecutionCommand`,
`com.cl2.integration.application.exception.FlowExecutionInvalidException`, `com.cl2.integration.domain.model.FlowExecution`,
`com.cl2.integration.domain.model.FlowExecutionStatus`) and the field:

```java
    @MockitoBean
    private FlowMetricsService metricsService;
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -pl application -am test -Dtest=FlowControllerTest`
Expected: FAIL to compile — `FlowController` has no `/executions` or `/metrics/summary` mappings, and no `FlowMetricsService` dependency yet.

- [ ] **Step 4: Add the endpoints to `FlowController`**

Add these imports to `FlowController.java`: `com.cl2.integration.adapter.in.web.dto.FlowExecutionResponse`,
`com.cl2.integration.adapter.in.web.dto.FlowMetricsSummaryResponse`,
`com.cl2.integration.adapter.in.web.dto.ReportFlowExecutionRequest`,
`com.cl2.integration.application.FlowMetricsService`,
`com.cl2.integration.application.command.ReportFlowExecutionCommand`.

Add a constructor field and update the constructor:

```java
    private final FlowService service;
    private final FlowMetricsService metricsService;
    private final ObjectMapper objectMapper;

    public FlowController(FlowService service, FlowMetricsService metricsService, ObjectMapper objectMapper) {
        this.service = service;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }
```

Add the two endpoints (place `metrics/summary` before `/{flowId}` mappings is not required — Spring's literal-segment routing already prioritizes `/metrics/summary` over `/{flowId}` — but keep it grouped near the top for readability):

```java
    @GetMapping("/metrics/summary")
    public FlowMetricsSummaryResponse metricsSummary() {
        return FlowMetricsSummaryResponse.from(metricsService.summarize(TenantContext.requireTenantId()));
    }

    @PostMapping("/{flowId}/executions")
    @ResponseStatus(HttpStatus.CREATED)
    public FlowExecutionResponse reportExecution(@PathVariable UUID flowId,
                                                  @Valid @RequestBody ReportFlowExecutionRequest request) {
        ReportFlowExecutionCommand command = new ReportFlowExecutionCommand(request.flowVersionNumber(),
                request.status(), request.startedAt(), request.finishedAt(), request.errorMessage());
        return FlowExecutionResponse.from(metricsService.report(TenantContext.requireTenantId(), flowId, command));
    }
```

- [ ] **Step 5: Map the new exception to 422 in `ApiExceptionHandler`**

```java
    @ExceptionHandler(FlowExecutionInvalidException.class)
    ProblemDetail handleFlowExecutionInvalid(FlowExecutionInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "FLOW_EXECUTION_INVALID", exception.getMessage(), request);
    }
```

Add the import `com.cl2.integration.application.exception.FlowExecutionInvalidException` at the top of `ApiExceptionHandler.java`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl application -am test -Dtest=FlowControllerTest`
Expected: PASS (all cases, including the 5 new ones)

- [ ] **Step 7: Run the full backend test suite**

Run: `docker compose up -d mysql redis kafka && mvn test`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add application/src/main/java/com/cl2/integration/adapter/in/web/dto/ReportFlowExecutionRequest.java application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowExecutionResponse.java application/src/main/java/com/cl2/integration/adapter/in/web/dto/FlowMetricsSummaryResponse.java application/src/main/java/com/cl2/integration/adapter/in/web/FlowController.java application/src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java application/src/test/java/com/cl2/integration/adapter/in/web/FlowControllerTest.java
git commit -m "feat: expose flow execution reporting and metrics summary endpoints"
```

---

### Task 6: BFF proxy routes

**Files:**
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts`
- Modify: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts`

**Interfaces:**
- Consumes: existing `forward(method, path, accessToken, body?)` helper in `gateway-proxy.service.ts` (same one `getFlows`/`publishFlow` use).
- Produces: `GatewayProxyService.getFlowMetricsSummary(accessToken: string): Promise<unknown>`, `GatewayProxyService.reportFlowExecution(accessToken: string, flowId: string, body: unknown): Promise<unknown>`; controller routes `GET flows/metrics/summary` and `POST flows/:flowId/executions`.

- [ ] **Step 1: Add the service methods**

In `gateway-proxy.service.ts`, immediately after `rollbackFlow` (or wherever the other `flows`-prefixed methods are grouped):

```ts
  getFlowMetricsSummary(accessToken: string): Promise<unknown> {
    return this.forward('get', '/api/v1/flows/metrics/summary', accessToken);
  }

  reportFlowExecution(accessToken: string, flowId: string, body: unknown): Promise<unknown> {
    return this.forward('post', `/api/v1/flows/${flowId}/executions`, accessToken, body);
  }
```

- [ ] **Step 2: Add the controller routes**

In `gateway-proxy.controller.ts`, immediately after the existing flow routes (near `listFlowVersions`/`publishFlow`):

```ts
  @Get('flows/metrics/summary')
  getFlowMetricsSummary(@Req() request: AuthenticatedRequest) {
    return this.gatewayProxy.getFlowMetricsSummary(request.session.tokens!.access_token!);
  }

  @Post('flows/:flowId/executions')
  @HttpCode(HttpStatus.CREATED)
  reportFlowExecution(@Req() request: AuthenticatedRequest, @Param('flowId') flowId: string, @Body() body: unknown) {
    return this.gatewayProxy.reportFlowExecution(request.session.tokens!.access_token!, flowId, body);
  }
```

Note: NestJS route matching also prioritizes literal segments (`flows/metrics/summary`) over parameterized ones (`flows/:flowId`) regardless of declaration order, so no reordering of existing routes is needed.

- [ ] **Step 3: Verify the BFF builds**

Run: `cd backoffice && npx nx build bff`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts
git commit -m "feat: proxy flow execution and metrics endpoints through the BFF"
```

---

### Task 7: Frontend — model, service, and KPI cards on `flow-list.component`

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow.model.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow.service.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow.service.spec.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.html`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.css`
- Modify: `backoffice/apps/integration-mfe/src/app/flow/flow-list.component.spec.ts`

**Interfaces:**
- Consumes: BFF routes from Task 6 (`GET /bff/api/v1/flows/metrics/summary`, `POST /bff/api/v1/flows/:flowId/executions`).
- Produces: `FlowService.getMetricsSummary(): Observable<FlowMetricsSummary>`, `FlowService.reportExecution(flowId: string, payload: ReportFlowExecutionPayload): Observable<unknown>`; `FlowListComponent.metrics: Signal<FlowMetricsSummary | null>`, `FlowListComponent.metricsUnavailable: Signal<boolean>`.

- [ ] **Step 1: Add types to `flow.model.ts`**

Append to `flow.model.ts`:

```ts
export type FlowExecutionStatus = 'SUCCESS' | 'FAILURE';

export interface FlowMetricsSummary {
  publishedFlowCount: number;
  executions24h: number;
  errorRatePct: number;
  p95DurationMs: number | null;
}

export interface ReportFlowExecutionPayload {
  flowVersionNumber: number;
  status: FlowExecutionStatus;
  startedAt: string;
  finishedAt: string;
  errorMessage?: string | null;
}
```

- [ ] **Step 2: Write the failing service test**

Add to `flow.service.spec.ts`:

```ts
  it('gets the flow metrics summary', () => {
    service.getMetricsSummary().subscribe((summary) => expect(summary.publishedFlowCount).toBe(3));
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 3,
      executions24h: 40,
      errorRatePct: 2.5,
      p95DurationMs: 810,
    });
  });

  it('reports a flow execution', () => {
    service
      .reportExecution('f-1', {
        flowVersionNumber: 1,
        status: 'SUCCESS',
        startedAt: '2026-08-30T00:00:00Z',
        finishedAt: '2026-08-30T00:00:01Z',
      })
      .subscribe();
    const request = http.expectOne('/bff/api/v1/flows/f-1/executions');
    expect(request.request.method).toBe('POST');
    request.flush({});
  });
```

- [ ] **Step 3: Run the service tests to verify they fail**

Run: `cd backoffice && npx nx test integration-mfe --test-file=flow.service.spec.ts`
Expected: FAIL — `getMetricsSummary`/`reportExecution` are not functions.

- [ ] **Step 4: Add the methods to `flow.service.ts`**

```ts
  getMetricsSummary(): Observable<FlowMetricsSummary> {
    return this.http.get<FlowMetricsSummary>(`${BASE_URL}/metrics/summary`);
  }

  reportExecution(flowId: string, payload: ReportFlowExecutionPayload): Observable<unknown> {
    return this.http.post(`${BASE_URL}/${flowId}/executions`, payload);
  }
```

Update the import line at the top of `flow.service.ts` to include the new types:

```ts
import { CreateFlowPayload, Flow, FlowMetricsSummary, FlowVersion, ReportFlowExecutionPayload, UpdateFlowDraftPayload } from './flow.model';
```

- [ ] **Step 5: Run the service tests to verify they pass**

Run: `cd backoffice && npx nx test integration-mfe --test-file=flow.service.spec.ts`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/flow/flow.model.ts backoffice/apps/integration-mfe/src/app/flow/flow.service.ts backoffice/apps/integration-mfe/src/app/flow/flow.service.spec.ts
git commit -m "feat: add flow metrics summary and execution reporting to FlowService"
```

- [ ] **Step 7: Write the failing component test**

Add to `flow-list.component.spec.ts`, replacing every place that currently does `listRequest.flush([]); fixture.detectChanges();` right after the initial `GET /bff/api/v1/flows` with a version that also flushes the metrics call (since the component now fires both on init), and add two new tests. First, update the shared setup pattern used by the two existing tests — after each `const listRequest = http.expectOne('/bff/api/v1/flows'); listRequest.flush([...]);` line, add:

```ts
    const metricsRequest = http.expectOne('/bff/api/v1/flows/metrics/summary');
    metricsRequest.flush({ publishedFlowCount: 0, executions24h: 0, errorRatePct: 0, p95DurationMs: null });
```

before the following `fixture.detectChanges();`. Then add:

```ts
  it('renders the KPI cards from the metrics summary', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/flows').flush([]);
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush({
      publishedFlowCount: 3,
      executions24h: 40,
      errorRatePct: 2.5,
      p95DurationMs: 810,
    });
    fixture.detectChanges();

    const values = fixture.nativeElement.querySelectorAll('.kpi-value');
    expect(values[0].textContent.trim()).toBe('3');
    expect(values[1].textContent.trim()).toBe('40');
    expect(values[2].textContent.trim()).toContain('2.5');
    expect(values[3].textContent.trim()).toContain('810');
  });

  it('shows unavailable KPI cards when the metrics call fails', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    http.expectOne('/bff/api/v1/flows').flush([]);
    http.expectOne('/bff/api/v1/flows/metrics/summary').flush('error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    const values = fixture.nativeElement.querySelectorAll('.kpi-value');
    expect(values[0].textContent.trim()).toBe('—');
    // The flows table must still work even though metrics failed.
    expect(fixture.nativeElement.querySelector('.state-message')).toBeFalsy();
  });
```

- [ ] **Step 8: Run the component tests to verify they fail**

Run: `cd backoffice && npx nx test integration-mfe --test-file=flow-list.component.spec.ts`
Expected: FAIL — no `.kpi-value` elements exist yet, and the two existing tests fail because the extra metrics HTTP call is never flushed (`http.verify()` in `afterEach` throws on the unflushed request).

- [ ] **Step 9: Wire the metrics summary into `flow-list.component.ts`**

Update the imports:

```ts
import { Flow, FlowMetricsSummary, FlowStatus } from './flow.model';
```

Add two signals and load the summary in `ngOnInit`:

```ts
  readonly metrics = signal<FlowMetricsSummary | null>(null);
  readonly metricsUnavailable = signal(false);

  ngOnInit(): void {
    this.load();
    this.loadMetrics();
  }

  loadMetrics(): void {
    this.metricsUnavailable.set(false);
    this.flowService.getMetricsSummary().subscribe({
      next: (metrics) => this.metrics.set(metrics),
      error: () => this.metricsUnavailable.set(true),
    });
  }
```

(Replace the existing single-line `ngOnInit(): void { this.load(); }` with the version above.)

- [ ] **Step 10: Add the KPI grid to `flow-list.component.html`**

Insert right after `<app-integration-tabs />` and before `<div class="page-header">`:

```html
  <div class="kpi-grid">
    <div class="card kpi">
      <span class="mono kpi-label">FLUJOS PUBLICADOS</span>
      <span class="kpi-value" [class.muted]="metricsUnavailable()">{{ metricsUnavailable() ? '—' : (metrics()?.publishedFlowCount ?? '—') }}</span>
      <span class="kpi-note">{{ metricsUnavailable() ? 'No disponible' : 'con versión activa' }}</span>
    </div>
    <div class="card kpi">
      <span class="mono kpi-label">EJECUCIONES 24H</span>
      <span class="kpi-value" [class.muted]="metricsUnavailable()">{{ metricsUnavailable() ? '—' : (metrics()?.executions24h ?? '—') }}</span>
      <span class="kpi-note">{{ metricsUnavailable() ? 'No disponible' : 'últimas 24 horas' }}</span>
    </div>
    <div class="card kpi">
      <span class="mono kpi-label">TASA DE ERROR</span>
      <span class="kpi-value" [class.muted]="metricsUnavailable()">{{ metricsUnavailable() || !metrics() ? '—' : (metrics()!.errorRatePct + '%') }}</span>
      <span class="kpi-note">{{ metricsUnavailable() ? 'No disponible' : 'últimas 24 horas' }}</span>
    </div>
    <div class="card kpi">
      <span class="mono kpi-label">P95 POR EJECUCIÓN</span>
      <span class="kpi-value" [class.muted]="metricsUnavailable()">{{ metricsUnavailable() || metrics()?.p95DurationMs == null ? '—' : (metrics()!.p95DurationMs + 'ms') }}</span>
      <span class="kpi-note">{{ metricsUnavailable() ? 'No disponible' : 'últimas 24 horas' }}</span>
    </div>
  </div>

- [ ] **Step 11: Add the KPI styles to `flow-list.component.css`**

Append (duplicated from `dashboard-page.component.css` — see spec's note on not extracting a shared abstraction for a second consumer):

```css
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.kpi { padding: 14px 16px; display: flex; flex-direction: column; gap: 6px; }
.kpi-label { font-size: 10px; letter-spacing: 0.08em; color: var(--text-dim); text-transform: uppercase; }
.kpi-value { font-size: 26px; font-weight: 600; letter-spacing: -0.02em; }
.kpi-value.muted { color: var(--text-dim); }
.kpi-note { font-size: 11.5px; color: var(--text-muted); }
```

- [ ] **Step 12: Run the component tests to verify they pass**

Run: `cd backoffice && npx nx test integration-mfe --test-file=flow-list.component.spec.ts`
Expected: PASS (all cases, including the 2 new ones)

- [ ] **Step 13: Run the full frontend test suite and build**

Run: `cd backoffice && npx nx test integration-mfe && npx nx build integration-mfe`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 14: Commit**

```bash
git add backoffice/apps/integration-mfe/src/app/flow/flow-list.component.ts backoffice/apps/integration-mfe/src/app/flow/flow-list.component.html backoffice/apps/integration-mfe/src/app/flow/flow-list.component.css backoffice/apps/integration-mfe/src/app/flow/flow-list.component.spec.ts
git commit -m "feat: render flow execution KPI cards on the Flows tab"
```

---

## Manual Verification

After Task 7, bring the stack up and confirm visually (this closes the loop on the original bug report):

1. `docker compose up -d --build mysql redis kafka app middleware` (per `README.md`).
2. `cd backoffice && npx nx serve shell` (or however the shell is normally served locally — check `backoffice/README.md` if `nx serve shell` isn't the right target).
3. Log in with `superset/superset`, navigate to `http://localhost:4000/integration/flows`.
4. Confirm the four KPI cards render (all showing `0`/`—` initially, since no executions exist yet — this is expected and matches the spec's "zero executions" behavior).
5. Optionally, use `curl` with a valid session cookie to `POST /bff/api/v1/flows/{flowId}/executions` a couple of times, then reload the page and confirm `EJECUCIONES 24H`/`TASA DE ERROR`/`P95 POR EJECUCIÓN` reflect the reported data.

