# Integration Sync Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a registered JDBC integration profile (e.g. the SAP HANA customer profile) actually execute on its configured cron schedule: resolve its `credentialRef`, run its extraction query, transform rows, publish them to the existing transactional outbox, and track a per-profile watermark — with no single slow/broken profile blocking any other.

**Architecture:** A `@Scheduled` tick scanner (every 30s) loads active JDBC profiles across all tenants, checks each one's `syncPolicy.cronExpression` against its last run time, and dispatches due profiles onto a small thread pool. Each dispatched run takes its own dynamic ShedLock (so a profile never overlaps itself), resolves its secret, builds a short-lived JDBC `DataSource`, extracts delta rows, transforms them through the existing `TransformationService`, writes them to the existing outbox, and advances a new per-profile watermark table — all in one transaction, so a failure never advances the watermark or leaves half-published rows.

**Tech Stack:** Spring Boot 3.4.5 / Java 21, Spring `@Scheduled` + `CronExpression`, ShedLock 5.13.0 (`LockingTaskExecutor` for dynamic per-profile locks), HikariCP via `DataSourceBuilder`, MySQL 8.4 (Flyway migrations), existing `SecretResolver`/`TransformationService`/`ResilienceExecutor`/outbox components.

**Spec:** `docs/superpowers/specs/2026-08-18-integration-sync-scheduler-design.md`

## Global Constraints

- Java 21, Spring Boot 3.4.5 (from `application/pom.xml`) — no new major dependency versions without checking compatibility.
- Follow the existing hexagonal package layout: domain ports in `com.cl2.integration.domain.port` / `com.cl2.integration.domain.model`, adapters under `com.cl2.integration.adapter.out.*`, cross-cutting runtime concerns under `com.cl2.integration.integration.*` (this plan's new code lives in `com.cl2.integration.integration.sync`).
- Tests run against the local MySQL from `compose.yaml` via `src/test/resources/application-test.yml` (`@ActiveProfiles("test")`) — this repo does **not** use Testcontainers today even though the dependency is present; do not introduce it here.
- Flyway migrations are additive only (`ADD COLUMN` / `CREATE TABLE`), matching `V3`/`V5` style; never edit an already-applied migration file.
- Commit after every task using the repo's existing commit style (`type: summary`, no ticket references).
- `IntegrationProfileConfiguration` and its DTOs pass configuration values as JSON strings/`JsonNode`, never typed sub-objects — keep new fields consistent with that (`extractionConfig` follows the exact same pattern as `mapping`/`syncPolicy`).
- New fields are appended at the **end** of existing records/constructors (never inserted in the middle) to avoid breaking the many existing positional-argument call sites in tests.

## Deviations from the approved spec (found while planning — see notes below)

1. **`extractionConfig` was never persisted.** The spec assumed a profile's extraction query/fetchSize/keyColumn was already available (`docs/superpowers/specs/2026-08-18-integration-sync-scheduler-design.md` §4 step 4), but `IntegrationProfileConfiguration` has no such field, no DB column, and no DTO support — it's silently dropped today. Task 1 adds it, following the exact pattern already used for `mapping`/`syncPolicy`. Confirmed with the user before proceeding.
2. **`lockAtMostFor` is no longer derived from `overlapBufferSeconds`.** The spec's §3 conflated two unrelated concerns (watermark overlap buffer vs. lock duration). This plan uses a dedicated `integration.sync.default-run-lock-at-most-for-seconds` property (default 600s) instead.
3. **A new `watermarkColumn` field is added to `ExtractionConfig`.** The spec's step 6 (`newWatermark = max(row[extractionConfig.keyColumn/timestamp column])`) never named which field holds the row timestamp. `keyColumn` is the business key (e.g. `CardCode`), not a timestamp — a separate field is required. Task 2 adds it.

---

### Task 1: Persist `extractionConfig` on integration profiles

**Files:**
- Modify: `src/main/java/com/cl2/integration/domain/model/IntegrationProfileConfiguration.java`
- Modify: `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntity.java`
- Modify: `src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java`
- Modify: `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java`
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileConfigurationRequest.java`
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/dto/CreateIntegrationProfileRequest.java`
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/dto/UpdateIntegrationProfileRequest.java`
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileResponse.java`
- Create: `src/main/resources/db/migration/V6__add_integration_profile_extraction_config.sql`
- Modify: `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java`
- Modify: `src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java` (6 call sites)
- Modify: `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntityTest.java`
- Modify: `src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java`
- Modify: `src/test/java/com/cl2/integration/integration/transformation/TransformationServiceIntegrationTest.java` (2 call sites)
- Modify: `src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java`
- Modify: `src/test/java/com/cl2/integration/integration/security/RuntimeSecurityResilienceIntegrationTest.java`

**Interfaces:**
- Produces: `IntegrationProfileConfiguration(IntegrationProtocol protocol, String connector, String adapter, String endpoint, String credentialRef, String mapping, String transformation, String syncPolicy, String retryPolicy, String rateLimitPolicy, String extractionConfig)` — `extractionConfig` is the 11th (last) parameter, a raw JSON string, same shape as `mapping`.
- Consumes (later tasks): `profile.configuration().extractionConfig()` returns the raw JSON string to be parsed into `ExtractionConfig` (Task 2/9).

- [ ] **Step 1: Write the failing test**

Edit `IntegrationProfilePersistenceAdapterTest.java`: update the existing `savesAndReadsAProfileWithConfiguration` test to include `extractionConfig`, and fix the already-stale migration-count assertion in `migratesTheIntegrationProfileSchema` (currently asserts `hasSize(4)` against a database that already has 5 applied migrations — confirmed by running the test before this change; adding `V6` makes the true count 6).

```java
    @Test
    void migratesTheIntegrationProfileSchema() throws SQLException {
        Set<String> columns;
        try (var connection = dataSource.getConnection();
             var resultSet = connection.getMetaData().getColumns(connection.getCatalog(), null,
                     "integration_profile", null)) {
            columns = new java.util.HashSet<>();
            while (resultSet.next()) {
                columns.add(resultSet.getString("COLUMN_NAME"));
            }
        }

        assertThat(flyway.info().applied()).hasSize(6);
        assertThat(columns).contains(
                "tenant_id", "active", "version", "created_at", "updated_at",
                "protocol", "connector", "adapter", "endpoint", "credential_ref",
                "mapping_json", "transformation_json", "sync_policy_json",
                "retry_policy_json", "rate_limit_policy_json", "extraction_config_json"
        );
    }

    @Test
    void savesAndReadsAProfileWithConfiguration() throws Exception {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-vehicle-http", "https://sigo.test/api", "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}", "{\"status\":\"MAP_STATUS\"}", "{\"mode\":\"INCREMENTAL\"}",
                "{\"maxAttempts\":3,\"initialBackoffMs\":100}", "{\"requestsPerSecond\":10}",
                "{\"method\":\"GET\",\"path\":\"/vehicles\"}"
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), TENANT_ID, "orders", "erp",
                SyncDirection.BIDIRECTIONAL, SourceOfTruth.PLATFORM, config);

        IntegrationProfile saved = adapter.save(TENANT_ID, profile);

        IntegrationProfile found = adapter.findById(TENANT_ID, saved.id());

        assertThat(found.configuration()).isNotNull();
        assertThat(found.configuration().protocol()).isEqualTo(config.protocol());
        assertThat(found.configuration().connector()).isEqualTo(config.connector());
        assertThat(found.configuration().adapter()).isEqualTo(config.adapter());
        assertThat(found.configuration().endpoint()).isEqualTo(config.endpoint());
        assertThat(found.configuration().credentialRef()).isEqualTo(config.credentialRef());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(found.configuration().mapping())).isEqualTo(mapper.readTree(config.mapping()));
        assertThat(mapper.readTree(found.configuration().transformation())).isEqualTo(mapper.readTree(config.transformation()));
        assertThat(mapper.readTree(found.configuration().syncPolicy())).isEqualTo(mapper.readTree(config.syncPolicy()));
        assertThat(mapper.readTree(found.configuration().retryPolicy())).isEqualTo(mapper.readTree(config.retryPolicy()));
        assertThat(mapper.readTree(found.configuration().rateLimitPolicy())).isEqualTo(mapper.readTree(config.rateLimitPolicy()));
        assertThat(mapper.readTree(found.configuration().extractionConfig())).isEqualTo(mapper.readTree(config.extractionConfig()));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=IntegrationProfilePersistenceAdapterTest -DfailIfNoTests=false`
Expected: Compile error — `IntegrationProfileConfiguration` has no 11-arg constructor, and `extractionConfig()` does not exist.

- [ ] **Step 3: Add the field through domain, entity, DTOs, and migration**

`IntegrationProfileConfiguration.java` — add the field as the last parameter, keep it a `record`, and add one more `validateNoPlaintextPassword` call:

```java
package com.cl2.integration.domain.model;

import java.util.regex.Pattern;

public record IntegrationProfileConfiguration(
        IntegrationProtocol protocol,
        String connector,
        String adapter,
        String endpoint,
        String credentialRef,
        String mapping,
        String transformation,
        String syncPolicy,
        String retryPolicy,
        String rateLimitPolicy,
        String extractionConfig
) {
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(?i)\"password\"\\s*:");

    public IntegrationProfileConfiguration {
        if (protocol != null) {
            if (connector == null || connector.isBlank()) {
                throw new IllegalArgumentException("connector must not be blank when protocol is specified");
            }
            if (adapter == null || adapter.isBlank()) {
                throw new IllegalArgumentException("adapter must not be blank when protocol is specified");
            }
        }
        if (credentialRef != null && credentialRef.isBlank()) {
            throw new IllegalArgumentException("credentialRef must not be blank when specified");
        }
        validateNoPlaintextPassword(mapping, "mapping");
        validateNoPlaintextPassword(transformation, "transformation");
        validateNoPlaintextPassword(syncPolicy, "syncPolicy");
        validateNoPlaintextPassword(retryPolicy, "retryPolicy");
        validateNoPlaintextPassword(rateLimitPolicy, "rateLimitPolicy");
        validateNoPlaintextPassword(extractionConfig, "extractionConfig");
    }

    private static void validateNoPlaintextPassword(String value, String fieldName) {
        if (value != null && PASSWORD_PATTERN.matcher(value).find()) {
            throw new IllegalArgumentException(fieldName + " must not contain plaintext password fields");
        }
    }
}
```

`IntegrationProfileJpaEntity.java` — add the column field, constructor assignment, `toDomain()` inclusion (both in the "has any config" check and the constructor call):

```java
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "extraction_config_json", columnDefinition = "JSON")
    private String extractionConfigJson;
```
Add this field declaration directly below `rateLimitPolicyJson`. In the private constructor, after `this.rateLimitPolicyJson = config.rateLimitPolicy();` add:
```java
            this.extractionConfigJson = config.extractionConfig();
```
In `toDomain()`, add `extractionConfigJson != null` to the existing `||` chain, and pass `extractionConfigJson` as the last constructor argument:
```java
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
```

`SpringDataIntegrationProfileRepository.java` — add `extraction_config_json` to the update query and a new `@Param`:
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
            @Param("updatedAt") java.time.Instant updatedAt);
```

`IntegrationProfilePersistenceAdapter.java` — add one more positional argument to the `updateIfVersionMatches` call:
```java
            int updatedRows = repository.updateIfVersionMatches(
                    profile.tenantId(), profile.id(), profile.version() - 1,
                    profile.businessDomain(), profile.externalSource(), profile.direction(), profile.sourceOfTruth(),
                    config != null ? config.protocol() : null,
                    config != null ? config.connector() : null,
                    config != null ? config.adapter() : null,
                    config != null ? config.endpoint() : null,
                    config != null ? config.credentialRef() : null,
                    config != null ? config.mapping() : null,
                    config != null ? config.transformation() : null,
                    config != null ? config.syncPolicy() : null,
                    config != null ? config.retryPolicy() : null,
                    config != null ? config.rateLimitPolicy() : null,
                    config != null ? config.extractionConfig() : null,
                    profile.active(), profile.updatedAt());
```

`IntegrationProfileConfigurationRequest.java` — add the field, include it in `hasAnyConfiguration()`, and pass it through `toDomain()`:
```java
public record IntegrationProfileConfigurationRequest(
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
    public boolean hasAnyConfiguration() {
        return protocol != null || connector != null || adapter != null || endpoint != null
                || credentialRef != null || mapping != null || transformation != null
                || syncPolicy != null || retryPolicy != null || rateLimitPolicy != null
                || extractionConfig != null;
    }

    public IntegrationProfileConfiguration toDomain(ObjectMapper objectMapper) {
        if (!hasAnyConfiguration()) {
            return null;
        }
        return new IntegrationProfileConfiguration(
                protocol,
                connector,
                adapter,
                endpoint,
                credentialRef,
                toJsonString(mapping, objectMapper),
                toJsonString(transformation, objectMapper),
                toJsonString(syncPolicy, objectMapper),
                toJsonString(retryPolicy, objectMapper),
                toJsonString(rateLimitPolicy, objectMapper),
                toJsonString(extractionConfig, objectMapper)
        );
    }

    private static String toJsonString(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse configuration JSON node", e);
        }
    }
}
```

`CreateIntegrationProfileRequest.java`:
```java
public record CreateIntegrationProfileRequest(
        @NotBlank String businessDomain,
        @NotBlank String externalSource,
        @NotNull SyncDirection syncDirection,
        @NotNull SourceOfTruth sourceOfTruth,
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
    public IntegrationProfileConfigurationRequest configurationRequest() {
        return new IntegrationProfileConfigurationRequest(
                protocol, connector, adapter, endpoint, credentialRef,
                mapping, transformation, syncPolicy, retryPolicy, rateLimitPolicy, extractionConfig
        );
    }
}
```

`UpdateIntegrationProfileRequest.java`:
```java
public record UpdateIntegrationProfileRequest(
        @NotBlank String businessDomain,
        @NotBlank String externalSource,
        @NotNull SyncDirection syncDirection,
        @NotNull SourceOfTruth sourceOfTruth,
        @NotNull @PositiveOrZero Long expectedVersion,
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
    public IntegrationProfileConfigurationRequest configurationRequest() {
        return new IntegrationProfileConfigurationRequest(
                protocol, connector, adapter, endpoint, credentialRef,
                mapping, transformation, syncPolicy, retryPolicy, rateLimitPolicy, extractionConfig
        );
    }
}
```

`IntegrationProfileResponse.java` — add to `ConfigurationResponse`:
```java
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
```

`V6__add_integration_profile_extraction_config.sql`:
```sql
ALTER TABLE integration_profile
    ADD COLUMN extraction_config_json JSON NULL AFTER rate_limit_policy_json;
```

- [ ] **Step 4: Fix every other existing positional `new IntegrationProfileConfiguration(...)` call site**

Adding an 11th constructor parameter breaks compilation everywhere the record is built positionally with exactly 10 arguments. Confirmed by searching the whole test suite — 7 more files do this (8 call sites total, all outside `IntegrationProfilePersistenceAdapterTest`, already fixed in Step 3). None of these tests care about `extractionConfig`, so append a trailing `null` argument to each:

`src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java` — 6 call sites (lines ~46-49, ~51-54, ~59-62, ~67-71, ~79-82, ~182-193). Each currently ends its argument list with `..., null, null, null, null, null))` or `..., "{\"requestsPerSecond\":10}"` (last one, multi-line). Append one more `null` (or `, null` for the multi-line one) to each so the call has 11 arguments, e.g. the first one becomes:
```java
        assertThatThrownBy(() -> new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, null, "adapter", null, null,
                null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
```
and the multi-line helper at the end of the file becomes:
```java
    private IntegrationProfileConfiguration configuration() {
        return new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "sigo",
                "sigo-vehicle-http",
                "https://sigo.test/api",
                "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}",
                "{\"status\":\"MAP_STATUS\"}",
                "{\"mode\":\"INCREMENTAL\"}",
                "{\"maxAttempts\":3,\"initialBackoffMs\":100}",
                "{\"requestsPerSecond\":10}",
                null
        );
    }
```
Apply the same "append one trailing `null`" edit to the other 5 call sites in this file, matching each one's existing multi-line/single-line style.

`src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntityTest.java` (line ~39-43):
```java
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-vehicle-http", "https://sigo.test/api", "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}", "{\"status\":\"MAP_STATUS\"}", "{\"mode\":\"INCREMENTAL\"}",
                "{\"maxAttempts\":3,\"initialBackoffMs\":100}", "{\"requestsPerSecond\":10}", null
        );
```

`src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java` (line ~69-72):
```java
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-vehicle-http", "https://sigo.test/api", "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}", null, null, "{\"maxAttempts\":3,\"initialBackoffMs\":100}",
                "{\"requestsPerSecond\":10}", null);
```

`src/test/java/com/cl2/integration/integration/transformation/TransformationServiceIntegrationTest.java` — 2 call sites:
```java
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-adapter", "http://external", null, mapping, null, null, null, null, null
        );
```
```java
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sap", "sap-adapter", "http://sap", null, null, jslt, null, null, null, null
        );
```

`src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java` (line ~175-178):
```java
        return new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-vehicle-http", "https://sigo.test/api", "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}", null, null, "{\"maxAttempts\":3,\"initialBackoffMs\":100}",
                "{\"requestsPerSecond\":10}", null);
```

`src/test/java/com/cl2/integration/integration/security/RuntimeSecurityResilienceIntegrationTest.java` (line ~40-43):
```java
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sap", "sap-customer-adapter", "https://sap.corp.internal/api",
                credentialRef, null, null, null, null, null, null
        );
```

- [ ] **Step 5: Run the full test suite to verify everything compiles and passes**

Run: `mvn -q -pl application -am test`
Expected: PASS (this is the only way to be sure every affected file was actually caught — a per-class run in Step 4 would miss any positional call site not listed above)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/cl2/integration/domain/model/IntegrationProfileConfiguration.java \
        src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntity.java \
        src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java \
        src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java \
        src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileConfigurationRequest.java \
        src/main/java/com/cl2/integration/adapter/in/web/dto/CreateIntegrationProfileRequest.java \
        src/main/java/com/cl2/integration/adapter/in/web/dto/UpdateIntegrationProfileRequest.java \
        src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileResponse.java \
        src/main/resources/db/migration/V6__add_integration_profile_extraction_config.sql \
        src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java \
        src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java \
        src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntityTest.java \
        src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java \
        src/test/java/com/cl2/integration/integration/transformation/TransformationServiceIntegrationTest.java \
        src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java \
        src/test/java/com/cl2/integration/integration/security/RuntimeSecurityResilienceIntegrationTest.java
git commit -m "feat: persist extractionConfig on integration profiles"
```

---

### Task 2: Add `watermarkColumn` to `ExtractionConfig`

**Files:**
- Modify: `src/main/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfig.java`
- Modify: `src/test/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfigTest.java`
- Modify: `src/test/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapterTest.java` (2 positional call sites — this pre-existing test builds `ExtractionConfig` positionally against an H2 in-memory database, independent of `GenericJdbcAdapterContextTest` added in Task 6)

**Interfaces:**
- Produces: `ExtractionConfig.watermarkColumn()` — `String`, nullable (only required for JDBC profiles, enforced at runtime by the orchestrator in Task 9, not by this record).

- [ ] **Step 1: Write the failing test**

Edit `shouldParseJdbcExtractionConfig` in `ExtractionConfigTest.java`:
```java
    @Test
    void shouldParseJdbcExtractionConfig() throws Exception {
        String json = """
            {
                "query": "SELECT * FROM KNA1 WHERE AEDAT >= :lastSyncWithBuffer",
                "watermarkParam": "lastSyncWithBuffer",
                "keyColumn": "KUNNR",
                "watermarkColumn": "AEDAT",
                "fetchSize": 500
            }
            """;
        ExtractionConfig config = objectMapper.readValue(json, ExtractionConfig.class);
        assertEquals("SELECT * FROM KNA1 WHERE AEDAT >= :lastSyncWithBuffer", config.query());
        assertEquals("lastSyncWithBuffer", config.watermarkParam());
        assertEquals("KUNNR", config.keyColumn());
        assertEquals("AEDAT", config.watermarkColumn());
        assertEquals(500, config.fetchSize());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=ExtractionConfigTest -DfailIfNoTests=false`
Expected: Compile error — `config.watermarkColumn()` does not exist.

- [ ] **Step 3: Fix the two existing positional `ExtractionConfig` call sites**

`GenericJdbcAdapterTest.java` builds two `ExtractionConfig` instances positionally with 11 args; append `, null` (no watermark column needed for either H2-backed test):
```java
        ExtractionConfig config = new ExtractionConfig(
                "SELECT ID AS customerId, NAME AS legalName FROM CUSTOMERS WHERE UPDATED_AT >= :lastSyncWithBuffer",
                "lastSyncWithBuffer", "customerId", 500, "GET", null, null, null, "$", "ISO_8601", "customerId", null
        );
```
```java
        ExtractionConfig invalidConfig = new ExtractionConfig(
                "DELETE FROM CUSTOMERS WHERE ID = 'C1'",
                "lastSyncWithBuffer", "customerId", 500, "GET", null, null, null, "$", "ISO_8601", "customerId", null
        );
```

- [ ] **Step 4: Add the field**

```java
package com.cl2.integration.adapter.out.generic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionConfig(
        String query,
        String watermarkParam,
        String keyColumn,
        Integer fetchSize,
        String method,
        String path,
        Map<String, String> queryParams,
        Map<String, String> headers,
        String responseJsonPath,
        String watermarkFormat,
        String keyProperty,
        String watermarkColumn
) {
    public ExtractionConfig {
        if (watermarkParam == null || watermarkParam.isBlank()) {
            watermarkParam = "lastSyncWithBuffer";
        }
        if (fetchSize == null || fetchSize <= 0) {
            fetchSize = 500;
        }
        if (method == null || method.isBlank()) {
            method = "GET";
        }
        if (responseJsonPath == null || responseJsonPath.isBlank()) {
            responseJsonPath = "$";
        }
        if (watermarkFormat == null || watermarkFormat.isBlank()) {
            watermarkFormat = "ISO_8601";
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -pl application -am test -Dtest=ExtractionConfigTest,GenericJdbcAdapterTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfig.java \
        src/test/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfigTest.java \
        src/test/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapterTest.java
git commit -m "feat: add watermarkColumn to ExtractionConfig for JDBC row-timestamp tracking"
```

---

### Task 3: Sync state (watermark) persistence

**Files:**
- Create: `src/main/resources/db/migration/V7__create_integration_sync_state.sql`
- Create: `src/main/java/com/cl2/integration/integration/sync/SyncRunStatus.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/SyncState.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/SyncStateRepository.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/SyncStateJpaEntity.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/SpringDataSyncStateRepository.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/SyncStatePersistenceAdapter.java`
- Create: `src/test/java/com/cl2/integration/integration/sync/SyncStatePersistenceAdapterTest.java`
- Modify: `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java` (bump migration count again)

**Interfaces:**
- Produces:
  - `enum SyncRunStatus { SUCCESS, FAILED }`
  - `record SyncState(UUID profileId, Instant lastWatermark, Instant lastRunStartedAt, SyncRunStatus lastRunStatus, String lastError)`
  - `interface SyncStateRepository { Optional<SyncState> find(UUID profileId); void upsert(SyncState state); }`
- Consumes (later tasks): Task 7 (`SyncStateRecorder`), Task 9 (`IntegrationSyncOrchestrator`), Task 10 (`IntegrationSyncScheduler`) all inject `SyncStateRepository`.

- [ ] **Step 1: Write the failing test**

Create `SyncStatePersistenceAdapterTest.java`:
```java
package com.cl2.integration.integration.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class SyncStatePersistenceAdapterTest {

    @Autowired
    private SyncStateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        jdbcTemplate.update("DELETE FROM integration_profile");
        jdbcTemplate.update(
                "INSERT INTO integration_profile (id, tenant_id, business_domain, external_source, sync_direction, source_of_truth, active, version, created_at, updated_at) "
                + "VALUES (UNHEX(REPLACE(?, '-', '')), UNHEX(REPLACE(?, '-', '')), 'customers', 'sap-hana', 'INBOUND', 'EXTERNAL', 1, 0, NOW(6), NOW(6))",
                PROFILE_ID.toString(), UUID.randomUUID().toString());
    }

    private static final UUID PROFILE_ID = UUID.randomUUID();

    @Test
    void returnsEmptyWhenNoStateRecordedYet() {
        assertThat(repository.find(PROFILE_ID)).isEmpty();
    }

    @Test
    void insertsAndReadsBackANewState() {
        Instant watermark = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant startedAt = watermark.minusSeconds(5);
        repository.upsert(new SyncState(PROFILE_ID, watermark, startedAt, SyncRunStatus.SUCCESS, null));

        SyncState found = repository.find(PROFILE_ID).orElseThrow();

        assertThat(found.profileId()).isEqualTo(PROFILE_ID);
        assertThat(found.lastWatermark()).isEqualTo(watermark);
        assertThat(found.lastRunStartedAt()).isEqualTo(startedAt);
        assertThat(found.lastRunStatus()).isEqualTo(SyncRunStatus.SUCCESS);
        assertThat(found.lastError()).isNull();
    }

    @Test
    void upsertOverwritesThePreviousStateForTheSameProfile() {
        Instant firstWatermark = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.upsert(new SyncState(PROFILE_ID, firstWatermark, firstWatermark, SyncRunStatus.SUCCESS, null));

        Instant secondStartedAt = firstWatermark.plusSeconds(600);
        repository.upsert(new SyncState(PROFILE_ID, firstWatermark, secondStartedAt, SyncRunStatus.FAILED, "boom"));

        SyncState found = repository.find(PROFILE_ID).orElseThrow();
        assertThat(found.lastRunStartedAt()).isEqualTo(secondStartedAt);
        assertThat(found.lastRunStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(found.lastError()).isEqualTo("boom");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=SyncStatePersistenceAdapterTest -DfailIfNoTests=false`
Expected: Compile error — none of the `com.cl2.integration.integration.sync` classes exist yet.

- [ ] **Step 3: Create the migration, domain types, entity, and adapter**

`V7__create_integration_sync_state.sql`:
```sql
CREATE TABLE integration_sync_state (
    profile_id           BINARY(16) NOT NULL,
    last_watermark        TIMESTAMP(6) NULL,
    last_run_started_at   TIMESTAMP(6) NULL,
    last_run_status        VARCHAR(20) NULL,
    last_error             VARCHAR(1000) NULL,
    PRIMARY KEY (profile_id),
    CONSTRAINT fk_sync_state_profile FOREIGN KEY (profile_id) REFERENCES integration_profile(id)
);
```

`SyncRunStatus.java`:
```java
package com.cl2.integration.integration.sync;

public enum SyncRunStatus {
    SUCCESS,
    FAILED
}
```

`SyncState.java`:
```java
package com.cl2.integration.integration.sync;

import java.time.Instant;
import java.util.UUID;

public record SyncState(
        UUID profileId,
        Instant lastWatermark,
        Instant lastRunStartedAt,
        SyncRunStatus lastRunStatus,
        String lastError
) {}
```

`SyncStateRepository.java`:
```java
package com.cl2.integration.integration.sync;

import java.util.Optional;
import java.util.UUID;

public interface SyncStateRepository {
    Optional<SyncState> find(UUID profileId);
    void upsert(SyncState state);
}
```

`SyncStateJpaEntity.java`:
```java
package com.cl2.integration.integration.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "integration_sync_state")
class SyncStateJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(name = "profile_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID profileId;

    @Column(name = "last_watermark", columnDefinition = "TIMESTAMP(6)")
    private Instant lastWatermark;

    @Column(name = "last_run_started_at", columnDefinition = "TIMESTAMP(6)")
    private Instant lastRunStartedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 20)
    private SyncRunStatus lastRunStatus;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected SyncStateJpaEntity() {
    }

    static SyncStateJpaEntity from(SyncState state) {
        SyncStateJpaEntity entity = new SyncStateJpaEntity();
        entity.applyUpdate(state);
        return entity;
    }

    void applyUpdate(SyncState state) {
        this.profileId = state.profileId();
        this.lastWatermark = state.lastWatermark();
        this.lastRunStartedAt = state.lastRunStartedAt();
        this.lastRunStatus = state.lastRunStatus();
        this.lastError = state.lastError();
    }

    SyncState toDomain() {
        return new SyncState(profileId, lastWatermark, lastRunStartedAt, lastRunStatus, lastError);
    }
}
```

`SpringDataSyncStateRepository.java` — `profileId` is the `@Id`, so `CrudRepository.findById(UUID)` already covers lookup by profile; no custom query method needed:
```java
package com.cl2.integration.integration.sync;

import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

interface SpringDataSyncStateRepository extends CrudRepository<SyncStateJpaEntity, UUID> {
}
```

`SyncStatePersistenceAdapter.java`:
```java
package com.cl2.integration.integration.sync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class SyncStatePersistenceAdapter implements SyncStateRepository {

    private final SpringDataSyncStateRepository repository;

    SyncStatePersistenceAdapter(SpringDataSyncStateRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SyncState> find(UUID profileId) {
        return repository.findById(profileId).map(SyncStateJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public void upsert(SyncState state) {
        SyncStateJpaEntity entity = repository.findById(state.profileId())
                .orElseGet(SyncStateJpaEntity::new);
        entity.applyUpdate(state);
        repository.save(entity);
    }
}
```

Also bump the migration count assertion again in `IntegrationProfilePersistenceAdapterTest.migratesTheIntegrationProfileSchema` from `hasSize(6)` (set in Task 1) to `hasSize(7)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl application -am test -Dtest=SyncStatePersistenceAdapterTest,IntegrationProfilePersistenceAdapterTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V7__create_integration_sync_state.sql \
        src/main/java/com/cl2/integration/integration/sync/SyncRunStatus.java \
        src/main/java/com/cl2/integration/integration/sync/SyncState.java \
        src/main/java/com/cl2/integration/integration/sync/SyncStateRepository.java \
        src/main/java/com/cl2/integration/integration/sync/SyncStateJpaEntity.java \
        src/main/java/com/cl2/integration/integration/sync/SpringDataSyncStateRepository.java \
        src/main/java/com/cl2/integration/integration/sync/SyncStatePersistenceAdapter.java \
        src/test/java/com/cl2/integration/integration/sync/SyncStatePersistenceAdapterTest.java \
        src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java
git commit -m "feat: add per-profile sync state (watermark) persistence"
```

---

### Task 4: List active JDBC profiles across all tenants

**Files:**
- Modify: `src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java`
- Modify: `src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java`
- Modify: `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java`
- Modify: `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java`

**Interfaces:**
- Produces: `IntegrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol protocol) : List<IntegrationProfile>` — active profiles across **all** tenants matching the given protocol.
- Consumes (Task 10): `IntegrationSyncScheduler.tick()` calls this with `IntegrationProtocol.JDBC`.

- [ ] **Step 1: Write the failing test**

Add `import java.util.List;` to `IntegrationProfilePersistenceAdapterTest.java`'s imports (not currently imported in this file), then add:
```java
    @Test
    void findsActiveProfilesByProtocolAcrossAllTenants() {
        IntegrationProfileConfiguration jdbcConfig = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "generic-jdbc", "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration", "secret/sap/hana",
                null, null, "{\"cronExpression\":\"0 */10 * * * *\"}", null, null,
                "{\"query\":\"SELECT 1\",\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"id\",\"watermarkColumn\":\"updated_at\"}"
        );
        IntegrationProfile jdbcProfileTenantOne = adapter.save(TENANT_ID,
                IntegrationProfile.create(UUID.randomUUID(), TENANT_ID, "customers", "sap-hana",
                        SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, jdbcConfig));
        IntegrationProfile jdbcProfileTenantTwo = adapter.save(OTHER_TENANT_ID,
                IntegrationProfile.create(UUID.randomUUID(), OTHER_TENANT_ID, "customers", "sap-hana",
                        SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, jdbcConfig));
        IntegrationProfile restProfile = adapter.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));
        IntegrationProfile inactiveJdbcProfile = adapter.save(TENANT_ID,
                IntegrationProfile.create(UUID.randomUUID(), TENANT_ID, "catalog", "sap-hana",
                        SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, jdbcConfig));
        adapter.save(TENANT_ID, inactiveJdbcProfile.deactivate());

        List<IntegrationProfile> jdbcProfiles = adapter.findAllActiveByProtocol(IntegrationProtocol.JDBC);

        assertThat(jdbcProfiles).extracting(IntegrationProfile::id)
                .containsExactlyInAnyOrder(jdbcProfileTenantOne.id(), jdbcProfileTenantTwo.id());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=IntegrationProfilePersistenceAdapterTest -DfailIfNoTests=false`
Expected: Compile error — `adapter.findAllActiveByProtocol(...)` does not exist.

- [ ] **Step 3: Add the method**

`IntegrationProfileRepository.java`:
```java
package com.cl2.integration.domain.port;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProtocol;
import java.util.List;
import java.util.UUID;

public interface IntegrationProfileRepository {

    IntegrationProfile save(UUID tenantId, IntegrationProfile profile);

    IntegrationProfile findById(UUID tenantId, UUID id);

    List<IntegrationProfile> findAll(UUID tenantId, boolean activeOnly);

    boolean existsActive(UUID tenantId, String businessDomain, String externalSource);

    List<IntegrationProfile> findAllActiveByProtocol(IntegrationProtocol protocol);
}
```

`SpringDataIntegrationProfileRepository.java` — add one derived query method (no `@Query` needed):
```java
    List<IntegrationProfileJpaEntity> findAllByActiveTrueAndProtocol(IntegrationProtocol protocol);
```
(add this method to the interface, alongside the existing `findAllByTenantId...` methods; add `import com.cl2.integration.domain.model.IntegrationProtocol;` at the top)

`IntegrationProfilePersistenceAdapter.java` — implement the new port method:
```java
    @Override
    @Transactional(readOnly = true)
    public List<IntegrationProfile> findAllActiveByProtocol(IntegrationProtocol protocol) {
        return repository.findAllByActiveTrueAndProtocol(protocol).stream()
                .map(IntegrationProfileJpaEntity::toDomain)
                .toList();
    }
```
(add `import com.cl2.integration.domain.model.IntegrationProtocol;`)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl application -am test -Dtest=IntegrationProfilePersistenceAdapterTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java \
        src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java \
        src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java \
        src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java
git commit -m "feat: list active integration profiles by protocol across all tenants"
```

---

### Task 5: `JdbcDataSourceFactory` — build a short-lived DataSource from endpoint + resolved secret

**Files:**
- Modify: `application/pom.xml`
- Create: `src/main/java/com/cl2/integration/integration/sync/JdbcDataSourceFactory.java`
- Create: `src/test/java/com/cl2/integration/integration/sync/JdbcDataSourceFactoryTest.java`

**Interfaces:**
- Produces: `JdbcDataSourceFactory.create(String endpoint, ResolvedSecret secret) : HikariDataSource` — caller is responsible for closing it (it is `Closeable`/`AutoCloseable`).
- Consumes (Task 9): `IntegrationSyncOrchestrator` calls this once per run, inside a try-with-resources block.

- [ ] **Step 1: Add the SAP HANA JDBC driver dependency**

Edit `application/pom.xml`, add after the `mysql-connector-j` dependency:
```xml
        <dependency>
            <groupId>com.sap.cloud.db.jdbc</groupId>
            <artifactId>ngdbc</artifactId>
            <version>2.20.15</version>
            <scope>runtime</scope>
        </dependency>
```
This is required for the real `jdbc:sap://...` endpoint on the already-registered SAP HANA profile to actually connect in production; without it `DriverManager` throws `ClassNotFoundException` for `com.sap.db.jdbc.Driver` even though Spring Boot's `DataSourceBuilder` correctly infers that driver class name from the URL prefix.

- [ ] **Step 2: Write the failing test**

Create `JdbcDataSourceFactoryTest.java` — connects to the same local test MySQL the rest of the suite already uses, standing in for a JDBC-reachable database:
```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.integration.security.ResolvedSecret;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class JdbcDataSourceFactoryTest {

    @Autowired
    private JdbcDataSourceFactory factory;

    @Test
    void buildsAWorkingDataSourceFromEndpointAndSecret() {
        ResolvedSecret secret = ResolvedSecret.basic("secret/test", "integration", "integration");

        try (HikariDataSource dataSource = factory.create(
                "jdbc:mysql://localhost:3306/integration?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
                secret)) {
            Integer result = new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
            assertThat(result).isEqualTo(1);
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=JdbcDataSourceFactoryTest -DfailIfNoTests=false`
Expected: Compile error — `JdbcDataSourceFactory` does not exist.

- [ ] **Step 4: Implement the factory**

```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.integration.security.ResolvedSecret;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.stereotype.Component;

@Component
public class JdbcDataSourceFactory {

    public HikariDataSource create(String endpoint, ResolvedSecret secret) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(endpoint)
                .username(secret.username())
                .password(secret.password())
                .build();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -pl application -am test -Dtest=JdbcDataSourceFactoryTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add application/pom.xml \
        src/main/java/com/cl2/integration/integration/sync/JdbcDataSourceFactory.java \
        src/test/java/com/cl2/integration/integration/sync/JdbcDataSourceFactoryTest.java
git commit -m "feat: build short-lived JDBC DataSources from resolved credentials"
```

---

### Task 6: Wire `GenericJdbcAdapter`/`SqlSecurityValidator` as Spring beans

**Files:**
- Modify: `src/main/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapter.java`
- Modify: `src/main/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidator.java`
- Create: `src/test/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapterContextTest.java`

**Interfaces:**
- Produces: `GenericJdbcAdapter` and `SqlSecurityValidator` are now injectable Spring beans (constructor/behavior unchanged).
- Consumes (Task 9): `IntegrationSyncOrchestrator` autowires `GenericJdbcAdapter`.

Both classes exist today but are plain POJOs with no `@Component`/`@Service` annotation. `GenericJdbcAdapterTest.java` already exercises them via manual `new GenericJdbcAdapter(new SqlSecurityValidator())` construction (against an H2 in-memory database) — that keeps working unchanged. Nothing in the *application* (production Spring context) instantiates or injects them today, which is what this task fixes, so Task 9's orchestrator can `@Autowired` them like any other bean.

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.adapter.out.generic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class GenericJdbcAdapterContextTest {

    @Autowired
    private GenericJdbcAdapter genericJdbcAdapter;

    @Test
    void isRegisteredAsASpringBean() {
        assertThat(genericJdbcAdapter).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=GenericJdbcAdapterContextTest -DfailIfNoTests=false`
Expected: FAIL — `NoSuchBeanDefinitionException` for `GenericJdbcAdapter`.

- [ ] **Step 3: Annotate both classes as components**

`SqlSecurityValidator.java` — add `@Component` and the import, keep everything else unchanged:
```java
package com.cl2.integration.adapter.out.generic.security;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class SqlSecurityValidator {
    // ... rest of the class body unchanged
}
```

`GenericJdbcAdapter.java` — add `@Component` and the import, keep everything else unchanged:
```java
package com.cl2.integration.adapter.out.generic;

import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.adapter.out.generic.security.SqlSecurityValidator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class GenericJdbcAdapter {
    // ... rest of the class body unchanged
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl application -am test -Dtest=GenericJdbcAdapterContextTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapter.java \
        src/main/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidator.java \
        src/test/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapterContextTest.java
git commit -m "feat: register GenericJdbcAdapter and SqlSecurityValidator as Spring beans"
```

---

### Task 7: `SyncStateRecorder` — failure bookkeeping in its own transaction

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/sync/SyncStateRecorder.java`
- Create: `src/test/java/com/cl2/integration/integration/sync/SyncStateRecorderTest.java`

**Interfaces:**
- Produces: `SyncStateRecorder.recordFailure(UUID profileId, Instant startedAt, String errorMessage) : void` — runs in `Propagation.REQUIRES_NEW` so it commits even when the caller's own transaction rolls back. Preserves the existing `lastWatermark` (a failure never advances or erases it).
- Consumes (Task 9): `IntegrationSyncOrchestrator` calls this from its `catch` block.

This is a **separate bean** from `IntegrationSyncOrchestrator` on purpose: `@Transactional(propagation = REQUIRES_NEW)` only takes effect through Spring's proxy, which is bypassed on a same-class (`this.method(...)`) call. Keeping it in its own bean is what makes the "record the failure even though the main transaction rolled back" behavior actually work.

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.integration.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class SyncStateRecorderTest {

    @Autowired
    private SyncStateRecorder recorder;

    @Autowired
    private SyncStateRepository syncStateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID PROFILE_ID = UUID.randomUUID();

    @BeforeEach
    void seedProfile() {
        jdbcTemplate.update("DELETE FROM integration_profile");
        jdbcTemplate.update(
                "INSERT INTO integration_profile (id, tenant_id, business_domain, external_source, sync_direction, source_of_truth, active, version, created_at, updated_at) "
                + "VALUES (UNHEX(REPLACE(?, '-', '')), UNHEX(REPLACE(?, '-', '')), 'customers', 'sap-hana', 'INBOUND', 'EXTERNAL', 1, 0, NOW(6), NOW(6))",
                PROFILE_ID.toString(), UUID.randomUUID().toString());
    }

    @Test
    void recordsAFailureWithoutTouchingTheExistingWatermark() {
        Instant existingWatermark = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        syncStateRepository.upsert(new SyncState(PROFILE_ID, existingWatermark, existingWatermark, SyncRunStatus.SUCCESS, null));

        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        recorder.recordFailure(PROFILE_ID, startedAt, "connection refused");

        SyncState found = syncStateRepository.find(PROFILE_ID).orElseThrow();
        assertThat(found.lastWatermark()).isEqualTo(existingWatermark);
        assertThat(found.lastRunStartedAt()).isEqualTo(startedAt);
        assertThat(found.lastRunStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(found.lastError()).isEqualTo("connection refused");
    }

    @Test
    void truncatesAnOverlyLongErrorMessage() {
        String longMessage = "x".repeat(2000);
        recorder.recordFailure(PROFILE_ID, Instant.now(), longMessage);

        SyncState found = syncStateRepository.find(PROFILE_ID).orElseThrow();
        assertThat(found.lastError()).hasSize(1000);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=SyncStateRecorderTest -DfailIfNoTests=false`
Expected: Compile error — `SyncStateRecorder` does not exist.

- [ ] **Step 3: Implement**

```java
package com.cl2.integration.integration.sync;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SyncStateRecorder {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final SyncStateRepository syncStateRepository;

    public SyncStateRecorder(SyncStateRepository syncStateRepository) {
        this.syncStateRepository = syncStateRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID profileId, Instant startedAt, String errorMessage) {
        Instant existingWatermark = syncStateRepository.find(profileId)
                .map(SyncState::lastWatermark)
                .orElse(null);
        String truncatedError = errorMessage == null
                ? null
                : errorMessage.substring(0, Math.min(errorMessage.length(), MAX_ERROR_LENGTH));
        syncStateRepository.upsert(new SyncState(profileId, existingWatermark, startedAt, SyncRunStatus.FAILED, truncatedError));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl application -am test -Dtest=SyncStateRecorderTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cl2/integration/integration/sync/SyncStateRecorder.java \
        src/test/java/com/cl2/integration/integration/sync/SyncStateRecorderTest.java
git commit -m "feat: record sync failures in their own transaction without disturbing the watermark"
```

---

### Task 8: Sync scheduling infrastructure — `SyncPolicy`, `LockingTaskExecutor` bean, properties, thread pool

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/sync/SyncPolicy.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/IntegrationSyncProperties.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/IntegrationSyncExecutorConfig.java`
- Modify: `src/main/java/com/cl2/integration/infrastructure/shedlock/ShedLockConfiguration.java`
- Create: `src/test/java/com/cl2/integration/integration/sync/SyncPolicyTest.java`
- Create: `src/test/java/com/cl2/integration/integration/sync/IntegrationSyncInfrastructureContextTest.java`

**Interfaces:**
- Produces:
  - `record SyncPolicy(String cronExpression, Integer overlapBufferSeconds)` with `overlapBufferSecondsOrZero() : int`.
  - `IntegrationSyncProperties.getDefaultRunLockAtMostForSeconds() : int` (default 600).
  - Spring bean `LockingTaskExecutor` (qualified by type, only one bean of this type in the context).
  - Spring bean named `"integrationSyncExecutor"` of type `Executor`.
- Consumes (Task 10): `IntegrationSyncScheduler` injects all four.

- [ ] **Step 1: Write the failing tests**

`SyncPolicyTest.java`:
```java
package com.cl2.integration.integration.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesCronExpressionAndOverlapBuffer() throws Exception {
        SyncPolicy policy = objectMapper.readValue(
                "{\"cronExpression\":\"0 */10 * * * *\",\"overlapBufferSeconds\":300}", SyncPolicy.class);

        assertThat(policy.cronExpression()).isEqualTo("0 */10 * * * *");
        assertThat(policy.overlapBufferSecondsOrZero()).isEqualTo(300);
    }

    @Test
    void defaultsOverlapBufferToZeroWhenAbsent() throws Exception {
        SyncPolicy policy = objectMapper.readValue("{\"cronExpression\":\"0 */10 * * * *\"}", SyncPolicy.class);

        assertThat(policy.overlapBufferSecondsOrZero()).isZero();
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        SyncPolicy policy = objectMapper.readValue(
                "{\"cronExpression\":\"0 */10 * * * *\",\"mode\":\"INCREMENTAL\"}", SyncPolicy.class);

        assertThat(policy.cronExpression()).isEqualTo("0 */10 * * * *");
    }
}
```

`IntegrationSyncInfrastructureContextTest.java`:
```java
package com.cl2.integration.integration.sync;

import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class IntegrationSyncInfrastructureContextTest {

    @Autowired
    private LockingTaskExecutor lockingTaskExecutor;

    @Autowired
    @Qualifier("integrationSyncExecutor")
    private Executor integrationSyncExecutor;

    @Autowired
    private IntegrationSyncProperties properties;

    @Test
    void wiresTheLockingTaskExecutorBean() {
        assertThat(lockingTaskExecutor).isNotNull();
    }

    @Test
    void wiresTheDedicatedSyncExecutorBean() {
        assertThat(integrationSyncExecutor).isNotNull();
    }

    @Test
    void defaultsRunLockAtMostForToTenMinutes() {
        assertThat(properties.getDefaultRunLockAtMostForSeconds()).isEqualTo(600);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl application -am test -Dtest=SyncPolicyTest,IntegrationSyncInfrastructureContextTest -DfailIfNoTests=false`
Expected: Compile errors — none of `SyncPolicy`, `IntegrationSyncProperties`, `integrationSyncExecutor` bean exist yet.

- [ ] **Step 3: Implement**

`SyncPolicy.java`:
```java
package com.cl2.integration.integration.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncPolicy(String cronExpression, Integer overlapBufferSeconds) {

    public int overlapBufferSecondsOrZero() {
        return overlapBufferSeconds == null ? 0 : overlapBufferSeconds;
    }
}
```

`IntegrationSyncProperties.java`:
```java
package com.cl2.integration.integration.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "integration.sync")
public class IntegrationSyncProperties {
    private int defaultRunLockAtMostForSeconds = 600;

    public int getDefaultRunLockAtMostForSeconds() { return defaultRunLockAtMostForSeconds; }
    public void setDefaultRunLockAtMostForSeconds(int defaultRunLockAtMostForSeconds) {
        this.defaultRunLockAtMostForSeconds = defaultRunLockAtMostForSeconds;
    }
}
```

`IntegrationSyncExecutorConfig.java`:
```java
package com.cl2.integration.integration.sync;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class IntegrationSyncExecutorConfig {

    @Bean(name = "integrationSyncExecutor")
    public Executor integrationSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("integration-sync-");
        executor.initialize();
        return executor;
    }
}
```

`ShedLockConfiguration.java` — add the `LockingTaskExecutor` bean alongside the existing `LockProvider` bean:
```java
package com.cl2.integration.infrastructure.shedlock;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfiguration {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }

    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl application -am test -Dtest=SyncPolicyTest,IntegrationSyncInfrastructureContextTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cl2/integration/integration/sync/SyncPolicy.java \
        src/main/java/com/cl2/integration/integration/sync/IntegrationSyncProperties.java \
        src/main/java/com/cl2/integration/integration/sync/IntegrationSyncExecutorConfig.java \
        src/main/java/com/cl2/integration/infrastructure/shedlock/ShedLockConfiguration.java \
        src/test/java/com/cl2/integration/integration/sync/SyncPolicyTest.java \
        src/test/java/com/cl2/integration/integration/sync/IntegrationSyncInfrastructureContextTest.java
git commit -m "feat: add sync scheduling infrastructure (policy parsing, locking executor, thread pool)"
```

---

### Task 9: `IntegrationSyncOrchestrator` — the per-profile pipeline

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/sync/IntegrationSyncException.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java`
- Create: `src/test/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestratorTest.java`

**Interfaces:**
- Consumes: `SecretResolver.resolve(String, UUID)`, `JdbcDataSourceFactory.create(String, ResolvedSecret)`, `GenericJdbcAdapter.extract(NamedParameterJdbcTemplate, ExtractionConfig, Instant)`, `TransformationService.transform(String, IntegrationProfile)`, `ResilienceExecutor.execute(UUID, String, Supplier<T>)`, `OutboxRepository.save(OutboxEvent)`, `SyncStateRepository.find/upsert`, `SyncStateRecorder.recordFailure(UUID, Instant, String)`.
- Produces: `IntegrationSyncOrchestrator.run(IntegrationProfile profile) : void`. Throws `IntegrationSyncException` (unchecked) on any failure, after recording it via `SyncStateRecorder`.
- Consumes (Task 10): `IntegrationSyncScheduler` calls `orchestrator.run(profile)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.adapter.out.generic.GenericJdbcAdapter;
import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxRepository;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.transformation.TransformationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationSyncOrchestratorTest {

    private SecretResolver secretResolver;
    private JdbcDataSourceFactory jdbcDataSourceFactory;
    private GenericJdbcAdapter genericJdbcAdapter;
    private TransformationService transformationService;
    private ResilienceExecutor resilienceExecutor;
    private OutboxRepository outboxRepository;
    private SyncStateRepository syncStateRepository;
    private SyncStateRecorder syncStateRecorder;
    private IntegrationSyncOrchestrator orchestrator;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        jdbcDataSourceFactory = mock(JdbcDataSourceFactory.class);
        genericJdbcAdapter = mock(GenericJdbcAdapter.class);
        transformationService = mock(TransformationService.class);
        resilienceExecutor = mock(ResilienceExecutor.class);
        outboxRepository = mock(OutboxRepository.class);
        syncStateRepository = mock(SyncStateRepository.class);
        syncStateRecorder = mock(SyncStateRecorder.class);

        orchestrator = new IntegrationSyncOrchestrator(
                secretResolver, jdbcDataSourceFactory, genericJdbcAdapter, transformationService,
                resilienceExecutor, outboxRepository, syncStateRepository, syncStateRecorder, new ObjectMapper());

        // ResilienceExecutor just runs the supplier synchronously in these tests
        when(resilienceExecutor.execute(any(), anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(2);
            return supplier.get();
        });
    }

    private IntegrationProfile profileWith(String extractionConfigJson, String syncPolicyJson) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "generic-jdbc", "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration", "secret/sap/hana",
                "{\"customerId\":\"CardCode\"}", null, syncPolicyJson, null, null, extractionConfigJson);
        return IntegrationProfile.rehydrate(profileId, tenantId, "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config, true, Instant.now(), Instant.now(), 0);
    }

    @Test
    void extractsTransformsAndPublishesRowsThenAdvancesTheWatermarkInOneRun() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode, updated_at FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"CardCode\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\",\"overlapBufferSeconds\":300}");

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(eq("jdbc:mysql://localhost:3306/integration"), eq(secret))).thenReturn(dataSource);

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(Map.of("CardCode", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp)));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

        orchestrator.run(profile);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(outboxCaptor.getValue().aggregateType()).isEqualTo("Customer");
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("customer.upserted");
        assertThat(outboxCaptor.getValue().payload()).isEqualTo("{\"customerId\":\"CLI-001\"}");

        ArgumentCaptor<SyncState> stateCaptor = ArgumentCaptor.forClass(SyncState.class);
        verify(syncStateRepository).upsert(stateCaptor.capture());
        assertThat(stateCaptor.getValue().profileId()).isEqualTo(profileId);
        assertThat(stateCaptor.getValue().lastRunStatus()).isEqualTo(SyncRunStatus.SUCCESS);
        assertThat(stateCaptor.getValue().lastWatermark()).isEqualTo(rowTimestamp.minusSeconds(300));

        verify(syncStateRecorder, never()).recordFailure(any(), any(), anyString());
    }

    @Test
    void recordsFailureAndDoesNotWriteToOutboxWhenExtractionFails() {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"CardCode\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\"}");

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> orchestrator.run(profile)).isInstanceOf(IntegrationSyncException.class);

        verify(outboxRepository, never()).save(any());
        verify(syncStateRepository, never()).upsert(any());
        verify(syncStateRecorder).recordFailure(eq(profileId), any(Instant.class), anyString());
    }

    @Test
    void failsFastWhenExtractionConfigIsMissing() {
        IntegrationProfile profile = profileWith(null, "{\"cronExpression\":\"0 */10 * * * *\"}");

        assertThatThrownBy(() -> orchestrator.run(profile)).isInstanceOf(IntegrationSyncException.class);

        verify(syncStateRecorder).recordFailure(eq(profileId), any(Instant.class), anyString());
        verify(jdbcDataSourceFactory, never()).create(anyString(), any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=IntegrationSyncOrchestratorTest -DfailIfNoTests=false`
Expected: Compile error — `IntegrationSyncOrchestrator` and `IntegrationSyncException` do not exist.

- [ ] **Step 3: Implement**

`IntegrationSyncException.java`:
```java
package com.cl2.integration.integration.sync;

public class IntegrationSyncException extends RuntimeException {
    public IntegrationSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`IntegrationSyncOrchestrator.java`:
```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.adapter.out.generic.GenericJdbcAdapter;
import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxRepository;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.transformation.TransformationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class IntegrationSyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncOrchestrator.class);

    private final SecretResolver secretResolver;
    private final JdbcDataSourceFactory jdbcDataSourceFactory;
    private final GenericJdbcAdapter genericJdbcAdapter;
    private final TransformationService transformationService;
    private final ResilienceExecutor resilienceExecutor;
    private final OutboxRepository outboxRepository;
    private final SyncStateRepository syncStateRepository;
    private final SyncStateRecorder syncStateRecorder;
    private final ObjectMapper objectMapper;

    public IntegrationSyncOrchestrator(
            SecretResolver secretResolver,
            JdbcDataSourceFactory jdbcDataSourceFactory,
            GenericJdbcAdapter genericJdbcAdapter,
            TransformationService transformationService,
            ResilienceExecutor resilienceExecutor,
            OutboxRepository outboxRepository,
            SyncStateRepository syncStateRepository,
            SyncStateRecorder syncStateRecorder,
            ObjectMapper objectMapper) {
        this.secretResolver = secretResolver;
        this.jdbcDataSourceFactory = jdbcDataSourceFactory;
        this.genericJdbcAdapter = genericJdbcAdapter;
        this.transformationService = transformationService;
        this.resilienceExecutor = resilienceExecutor;
        this.outboxRepository = outboxRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncStateRecorder = syncStateRecorder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void run(IntegrationProfile profile) {
        Instant startedAt = Instant.now();
        try {
            ExtractionConfig extractionConfig = readExtractionConfig(profile);
            if (extractionConfig.watermarkColumn() == null || extractionConfig.watermarkColumn().isBlank()) {
                throw new IllegalStateException("extractionConfig.watermarkColumn is required for JDBC profiles");
            }
            ResolvedSecret secret = secretResolver.resolve(profile.configuration().credentialRef(), profile.tenantId());
            Instant watermark = syncStateRepository.find(profile.id())
                    .map(SyncState::lastWatermark)
                    .orElse(Instant.EPOCH);

            List<Map<String, Object>> rows;
            try (HikariDataSource dataSource = jdbcDataSourceFactory.create(profile.configuration().endpoint(), secret)) {
                NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
                rows = resilienceExecutor.execute(profile.tenantId(), profile.configuration().connector(),
                        () -> genericJdbcAdapter.extract(jdbcTemplate, extractionConfig, watermark));
            }

            Instant maxRowTimestamp = watermark;
            for (Map<String, Object> row : rows) {
                String rowJson = objectMapper.writeValueAsString(row);
                String canonicalJson = transformationService.transform(rowJson, profile);
                UUID aggregateId = deriveAggregateId(profile.tenantId(), String.valueOf(row.get(extractionConfig.keyColumn())));
                outboxRepository.save(OutboxEvent.pending(profile.tenantId(), aggregateId, "Customer", "customer.upserted", canonicalJson));

                Instant rowTimestamp = readWatermarkTimestamp(row, extractionConfig.watermarkColumn());
                if (rowTimestamp.isAfter(maxRowTimestamp)) {
                    maxRowTimestamp = rowTimestamp;
                }
            }

            int overlapBufferSeconds = readOverlapBufferSeconds(profile);
            Instant advancedWatermark = rows.isEmpty() ? watermark : maxRowTimestamp.minusSeconds(overlapBufferSeconds);
            syncStateRepository.upsert(new SyncState(profile.id(), advancedWatermark, startedAt, SyncRunStatus.SUCCESS, null));
        } catch (Exception ex) {
            log.warn("Sync run failed for profile {}: {}", profile.id(), ex.getMessage());
            syncStateRecorder.recordFailure(profile.id(), startedAt, String.valueOf(ex.getMessage()));
            throw new IntegrationSyncException("Sync failed for profile " + profile.id(), ex);
        }
    }

    private ExtractionConfig readExtractionConfig(IntegrationProfile profile) {
        String json = profile.configuration() != null ? profile.configuration().extractionConfig() : null;
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Profile " + profile.id() + " has no extractionConfig");
        }
        try {
            return objectMapper.readValue(json, ExtractionConfig.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid extractionConfig JSON for profile " + profile.id(), ex);
        }
    }

    private int readOverlapBufferSeconds(IntegrationProfile profile) {
        String json = profile.configuration() != null ? profile.configuration().syncPolicy() : null;
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            return objectMapper.readValue(json, SyncPolicy.class).overlapBufferSecondsOrZero();
        } catch (Exception ex) {
            return 0;
        }
    }

    private UUID deriveAggregateId(UUID tenantId, String businessKey) {
        return UUID.nameUUIDFromBytes((tenantId + ":" + businessKey).getBytes(StandardCharsets.UTF_8));
    }

    private Instant readWatermarkTimestamp(Map<String, Object> row, String watermarkColumn) {
        Object value = row.get(watermarkColumn);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unsupported watermark column type: "
                + (value == null ? "null" : value.getClass()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl application -am test -Dtest=IntegrationSyncOrchestratorTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cl2/integration/integration/sync/IntegrationSyncException.java \
        src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java \
        src/test/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestratorTest.java
git commit -m "feat: add IntegrationSyncOrchestrator (resolve, extract, transform, publish, advance watermark)"
```

---

### Task 10: `IntegrationSyncScheduler` — the tick scanner

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/sync/IntegrationSyncScheduler.java`
- Create: `src/test/java/com/cl2/integration/integration/sync/IntegrationSyncSchedulerTest.java`

**Interfaces:**
- Consumes: `IntegrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol)`, `SyncStateRepository.find(UUID)`, `IntegrationSyncOrchestrator.run(IntegrationProfile)`, `LockingTaskExecutor.executeWithLock(Runnable, LockConfiguration)`, `Executor` (`integrationSyncExecutor`), `IntegrationSyncProperties.getDefaultRunLockAtMostForSeconds()`.
- Produces: `IntegrationSyncScheduler.tick() : void` — `@Scheduled` entry point; also directly callable from tests/other code without waiting for the real timer.

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationSyncSchedulerTest {

    private IntegrationProfileRepository integrationProfileRepository;
    private SyncStateRepository syncStateRepository;
    private IntegrationSyncOrchestrator orchestrator;
    private LockingTaskExecutor lockingTaskExecutor;
    private IntegrationSyncProperties properties;
    private IntegrationSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        integrationProfileRepository = mock(IntegrationProfileRepository.class);
        syncStateRepository = mock(SyncStateRepository.class);
        orchestrator = mock(IntegrationSyncOrchestrator.class);
        lockingTaskExecutor = mock(LockingTaskExecutor.class);
        properties = new IntegrationSyncProperties();
        Executor synchronousExecutor = Runnable::run;

        scheduler = new IntegrationSyncScheduler(
                integrationProfileRepository, syncStateRepository, orchestrator,
                lockingTaskExecutor, synchronousExecutor, new ObjectMapper(), properties);

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(lockingTaskExecutor).executeWithLock(any(Runnable.class), any(LockConfiguration.class));
    }

    private IntegrationProfile jdbcProfile(UUID id, String cronExpression, Instant createdAt) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "generic-jdbc", "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration", "secret/sap/hana",
                null, null, "{\"cronExpression\":\"" + cronExpression + "\"}", null, null,
                "{\"query\":\"SELECT 1\",\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"id\",\"watermarkColumn\":\"updated_at\"}");
        return IntegrationProfile.rehydrate(id, UUID.randomUUID(), "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config, true, createdAt, createdAt, 0);
    }

    @Test
    void dispatchesAProfileThatHasNeverRunAndWhoseCronIsAlreadyDue() {
        UUID profileId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS);
        IntegrationProfile profile = jdbcProfile(profileId, "0 * * * * *", createdAt); // every minute
        when(integrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol.JDBC)).thenReturn(List.of(profile));
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        scheduler.tick();

        verify(orchestrator).run(profile);
    }

    @Test
    void doesNotDispatchAProfileWhoseCronIsNotDueYet() {
        UUID profileId = UUID.randomUUID();
        Instant justRan = Instant.now();
        IntegrationProfile profile = jdbcProfile(profileId, "0 0 0 1 1 *", justRan); // once a year, Jan 1st
        when(integrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol.JDBC)).thenReturn(List.of(profile));
        when(syncStateRepository.find(profileId)).thenReturn(
                Optional.of(new SyncState(profileId, Instant.EPOCH, justRan, SyncRunStatus.SUCCESS, null)));

        scheduler.tick();

        verify(orchestrator, never()).run(any());
    }

    @Test
    void aFailingProfileDoesNotStopTheRestOfTheScan() {
        UUID brokenProfileId = UUID.randomUUID();
        UUID healthyProfileId = UUID.randomUUID();
        Instant longAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        IntegrationProfile brokenProfile = jdbcProfile(brokenProfileId, "0 * * * * *", longAgo);
        IntegrationProfile healthyProfile = jdbcProfile(healthyProfileId, "0 * * * * *", longAgo);
        when(integrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol.JDBC))
                .thenReturn(List.of(brokenProfile, healthyProfile));
        when(syncStateRepository.find(any())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(orchestrator).run(brokenProfile);

        scheduler.tick();

        verify(orchestrator).run(brokenProfile);
        verify(orchestrator).run(healthyProfile);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=IntegrationSyncSchedulerTest -DfailIfNoTests=false`
Expected: Compile error — `IntegrationSyncScheduler` does not exist.

- [ ] **Step 3: Implement**

```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;

@Component
public class IntegrationSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncScheduler.class);

    private final IntegrationProfileRepository integrationProfileRepository;
    private final SyncStateRepository syncStateRepository;
    private final IntegrationSyncOrchestrator orchestrator;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Executor integrationSyncExecutor;
    private final ObjectMapper objectMapper;
    private final IntegrationSyncProperties properties;

    public IntegrationSyncScheduler(
            IntegrationProfileRepository integrationProfileRepository,
            SyncStateRepository syncStateRepository,
            IntegrationSyncOrchestrator orchestrator,
            LockingTaskExecutor lockingTaskExecutor,
            @Qualifier("integrationSyncExecutor") Executor integrationSyncExecutor,
            ObjectMapper objectMapper,
            IntegrationSyncProperties properties) {
        this.integrationProfileRepository = integrationProfileRepository;
        this.syncStateRepository = syncStateRepository;
        this.orchestrator = orchestrator;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.integrationSyncExecutor = integrationSyncExecutor;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${integration.sync.tick-fixed-delay-ms:30000}")
    @SchedulerLock(name = "integration-sync-tick", lockAtMostFor = "25s")
    public void tick() {
        List<IntegrationProfile> profiles = integrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol.JDBC);
        for (IntegrationProfile profile : profiles) {
            try {
                if (isDue(profile)) {
                    dispatch(profile);
                }
            } catch (Exception ex) {
                log.warn("Failed to evaluate or dispatch sync for profile {}: {}", profile.id(), ex.getMessage());
            }
        }
    }

    private boolean isDue(IntegrationProfile profile) {
        SyncPolicy syncPolicy = parseSyncPolicy(profile);
        if (syncPolicy == null || syncPolicy.cronExpression() == null || syncPolicy.cronExpression().isBlank()) {
            return false;
        }
        CronExpression cronExpression = CronExpression.parse(syncPolicy.cronExpression());
        Instant anchorInstant = syncStateRepository.find(profile.id())
                .map(SyncState::lastRunStartedAt)
                .orElse(profile.createdAt());
        LocalDateTime anchor = LocalDateTime.ofInstant(anchorInstant, ZoneOffset.UTC);
        LocalDateTime next = cronExpression.next(anchor);
        return next != null && !next.isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }

    private SyncPolicy parseSyncPolicy(IntegrationProfile profile) {
        String json = profile.configuration() != null ? profile.configuration().syncPolicy() : null;
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SyncPolicy.class);
        } catch (Exception ex) {
            log.warn("Invalid syncPolicy JSON for profile {}: {}", profile.id(), ex.getMessage());
            return null;
        }
    }

    private void dispatch(IntegrationProfile profile) {
        LockConfiguration lockConfiguration = new LockConfiguration(
                Instant.now(), "sync:" + profile.id(),
                Duration.ofSeconds(properties.getDefaultRunLockAtMostForSeconds()), Duration.ofSeconds(1));
        Runnable task = () -> orchestrator.run(profile);
        integrationSyncExecutor.execute(() -> {
            try {
                lockingTaskExecutor.executeWithLock(task, lockConfiguration);
            } catch (Exception ex) {
                log.warn("Sync run failed for profile {}: {}", profile.id(), ex.getMessage());
            }
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl application -am test -Dtest=IntegrationSyncSchedulerTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cl2/integration/integration/sync/IntegrationSyncScheduler.java \
        src/test/java/com/cl2/integration/integration/sync/IntegrationSyncSchedulerTest.java
git commit -m "feat: add IntegrationSyncScheduler tick scanner for per-profile cron dispatch"
```

---

### Task 11: End-to-end test — a registered profile actually syncs

**Files:**
- Create: `src/test/java/com/cl2/integration/integration/sync/IntegrationSyncEndToEndTest.java`

**Interfaces:**
- Consumes everything built in Tasks 1–10 through the real Spring context (no mocks): registers a profile via the existing persistence layer with a scratch source table standing in for HANA (same convention as `JdbcDataSourceFactoryTest`), seeds `SecretResolver` the same way `RuntimeSecurityResilienceIntegrationTest` does, calls `scheduler.tick()` directly instead of waiting for the real 30s timer, and asserts against the `outbox` and `integration_sync_state` tables directly via `JdbcTemplate`.

- [ ] **Step 1: Write the test**

```java
package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class IntegrationSyncEndToEndTest {

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private IntegrationProfileRepository integrationProfileRepository;

    @Autowired
    private SecretResolver secretResolver;

    @Autowired
    private IntegrationSyncScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUpScratchSourceTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS scratch_customers");
        jdbcTemplate.execute("""
                CREATE TABLE scratch_customers (
                    card_code VARCHAR(50) PRIMARY KEY,
                    card_name VARCHAR(200),
                    updated_at TIMESTAMP(6)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO scratch_customers (card_code, card_name, updated_at) VALUES (?, ?, ?)",
                "CLI-001", "Acme Corp", java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_type = 'Customer'");
    }

    @Test
    void aDueJdbcProfileExtractsTransformsAndPublishesToTheOutbox() {
        String credentialRef = "secret/test/" + tenantId;
        secretResolver.putSecret(credentialRef, tenantId, ResolvedSecret.basic(credentialRef, "integration", "integration"));

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "generic-jdbc", "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
                credentialRef,
                "{\"customerId\":\"card_code\",\"legalName\":\"card_name\"}",
                null,
                "{\"cronExpression\":\"0 * * * * *\",\"overlapBufferSeconds\":0}",
                null, null,
                "{\"query\":\"SELECT card_code, card_name, updated_at FROM scratch_customers WHERE updated_at >= :lastSyncWithBuffer\","
                        + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"card_code\",\"watermarkColumn\":\"updated_at\"}");
        IntegrationProfile profile = integrationProfileRepository.save(tenantId, IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config));

        scheduler.tick();

        List<Map<String, Object>> outboxRows = jdbcTemplate.queryForList(
                "SELECT * FROM outbox WHERE tenant_id = UNHEX(REPLACE(?, '-', '')) AND aggregate_type = 'Customer'",
                tenantId.toString());
        assertThat(outboxRows).hasSize(1);
        assertThat(String.valueOf(outboxRows.get(0).get("payload"))).contains("Acme Corp");

        SyncState syncState = jdbcTemplate.queryForObject(
                "SELECT last_run_status, last_watermark FROM integration_sync_state WHERE profile_id = UNHEX(REPLACE(?, '-', ''))",
                (rs, rowNum) -> new SyncState(profile.id(),
                        rs.getTimestamp("last_watermark").toInstant(),
                        null,
                        SyncRunStatus.valueOf(rs.getString("last_run_status")),
                        null),
                profile.id().toString());
        assertThat(syncState.lastRunStatus()).isEqualTo(SyncRunStatus.SUCCESS);
        assertThat(syncState.lastWatermark()).isAfterOrEqualTo(Instant.now().minus(5, ChronoUnit.MINUTES));
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl application -am test -Dtest=IntegrationSyncEndToEndTest -DfailIfNoTests=false`
Expected: PASS. If it fails, the most likely causes, in order: (a) the `outbox` table's actual column names differ from what's assumed here — check `OutboxJpaEntity.java` and adjust the raw SQL; (b) MySQL returns `updated_at` as `LocalDateTime` rather than `Timestamp` — this is already handled by `IntegrationSyncOrchestrator.readWatermarkTimestamp`, but confirm via the test output; (c) the outbox table's `tenant_id`/`aggregate_type` column names — verify against `OutboxJpaEntity.java` before assuming.

- [ ] **Step 3: Run the full test suite to confirm nothing else regressed**

Run: `mvn -q -pl application -am test`
Expected: PASS (all tests, including every test touched or added in Tasks 1–11).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/cl2/integration/integration/sync/IntegrationSyncEndToEndTest.java
git commit -m "test: add end-to-end coverage for the integration sync scheduler pipeline"
```

---

## Post-plan note

After Task 11, the already-registered SAP HANA customer profile (`credentialRef: secret/sap/sigo-hana-credentials`) still needs two follow-ups outside this plan to actually run against real SAP HANA:
1. Its stored `syncPolicy.cronExpression` is currently `"0/10 * * * * *"` (every 10 seconds) — update it to `"0 */10 * * * *"` (every 10 minutes) via `PUT /api/v1/integration-profiles/{id}`.
2. Its `extractionConfig` needs to be set via the same endpoint now that the field is persisted (Task 1) — it was never submitted when the profile was originally created.
