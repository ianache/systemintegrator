# Value Lookups / Codifiers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide tenant-scoped value lookups (codifiers / value mapping) stored in the database with REST API management, integrated seamlessly into `FIELD_MAPPING` and `JSLT` transformation engines with default value fallback support.

**Architecture:** Create Flyway migration `V8__create_integration_value_lookup.sql`, domain entities, JPA persistence, repository, and REST controller for lookup management. Implement `ValueLookupService` with thread-safe caching. Integrate lookups into `FieldMappingPayloadTransformer` (via `FieldMappingRule.lookup`) and `JsltPayloadTransformer` (via custom extension function).

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring Data JPA, Flyway, MySQL 8.4, JSLT (Schibsted), JsonPath, JUnit 5, AssertJ.

## Global Constraints
- All paths must reside under `application/src/main` and `application/src/test`.
- Maintain 100% test pass rate across the reactor (`mvn test`).
- Enforce strict tenant isolation via `TenantContext`.

---

### Task 1: Flyway Migration, ValueLookup Domain Model, and JPA Persistence

**Files:**
- Create: `application/src/main/resources/db/migration/V8__create_integration_value_lookup.sql`
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/domain/ValueLookup.java`
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/ValueLookupJpaEntity.java`
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/SpringDataValueLookupRepository.java`
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/ValueLookupPersistenceAdapter.java`
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/domain/ValueLookupRepository.java`
- Test: `application/src/test/java/com/cl2/integration/integration/lookup/ValueLookupPersistenceAdapterTest.java`

**Interfaces:**
- Produces: `ValueLookup.create(UUID id, UUID tenantId, String externalSource, String catalogCode, String sourceValue, String targetValue, String description, boolean active)`
- Produces: `ValueLookupRepository.findTargetValue(UUID tenantId, String externalSource, String catalogCode, String sourceValue)`
- Produces: `ValueLookupRepository.findAll(UUID tenantId, String externalSource, String catalogCode)`

- [ ] **Step 1: Write failing test in ValueLookupPersistenceAdapterTest**
Verify saving, querying by `(tenantId, externalSource, catalogCode, sourceValue)`, and listing catalog items.

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn test -pl application "-Dtest=ValueLookupPersistenceAdapterTest"`
Expected: FAIL due to missing classes and migration.

- [ ] **Step 3: Implement Flyway migration, Entity, Domain Model, and Persistence Adapter**
Write `V8__create_integration_value_lookup.sql`, `ValueLookupJpaEntity.java`, `SpringDataValueLookupRepository.java`, and `ValueLookupPersistenceAdapter.java`.

- [ ] **Step 4: Run test to verify it passes**
Run: `mvn test -pl application "-Dtest=ValueLookupPersistenceAdapterTest"`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add application/src/main/resources/db/migration/V8__create_integration_value_lookup.sql application/src/main/java/com/cl2/integration/integration/lookup/ application/src/test/java/com/cl2/integration/integration/lookup/ValueLookupPersistenceAdapterTest.java
git commit -m "feat(lookup): add Flyway schema, domain model and JPA persistence for value lookups"
```

---

### Task 2: Implement `ValueLookupService` and REST API Controller

**Files:**
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/application/ValueLookupService.java`
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/ValueLookupController.java`
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/dto/CreateValueLookupRequest.java`
- Create: `application/src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/dto/ValueLookupResponse.java`
- Test: `application/src/test/java/com/cl2/integration/integration/lookup/ValueLookupControllerTest.java`

**Interfaces:**
- Produces: `ValueLookupService.lookup(UUID tenantId, String externalSource, String catalogCode, String sourceValue, String defaultValue)`
- Produces: `POST /api/v1/lookups`, `POST /api/v1/lookups/batch`, `GET /api/v1/lookups`, `DELETE /api/v1/lookups/{id}`

- [ ] **Step 1: Write failing test in ValueLookupControllerTest**
Test creating single lookup, batch upsert, querying by catalog, and deleting mappings with `TenantContext`.

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn test -pl application "-Dtest=ValueLookupControllerTest"`
Expected: FAIL due to missing controller and service.

- [ ] **Step 3: Implement ValueLookupService and ValueLookupController**
Implement caching in `ValueLookupService`, invalidation on write/delete, and REST endpoints in `ValueLookupController`.

- [ ] **Step 4: Run test to verify it passes**
Run: `mvn test -pl application "-Dtest=ValueLookupControllerTest"`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add application/src/main/java/com/cl2/integration/integration/lookup/application/ application/src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/ application/src/test/java/com/cl2/integration/integration/lookup/ValueLookupControllerTest.java
git commit -m "feat(lookup): add ValueLookupService and REST API controller"
```

---

### Task 3: Integrate Value Lookups into `FieldMappingPayloadTransformer`

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/integration/transformation/field/FieldMappingRule.java`
- Create: `application/src/main/java/com/cl2/integration/integration/transformation/field/LookupRule.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/transformation/field/FieldMappingPayloadTransformer.java`
- Test: `application/src/test/java/com/cl2/integration/integration/transformation/field/FieldMappingPayloadTransformerTest.java`

**Interfaces:**
- Consumes: `ValueLookupService.lookup(...)`
- Produces: `LookupRule(String catalogCode, String defaultValue)`

- [x] **Step 1: Write failing test in FieldMappingPayloadTransformerTest**
Add tests asserting that field mapping with `lookup: { catalogCode: "TIPO", defaultValue: "DEFAULT_VAL" }` translates matching source values and applies fallback when not found.

- [x] **Step 2: Run test to verify it fails**
Run: `mvn test -pl application "-Dtest=FieldMappingPayloadTransformerTest"`
Expected: FAIL

- [x] **Step 3: Update `FieldMappingRule.java` and `FieldMappingPayloadTransformer.java`**
Inject `ValueLookupService` into `FieldMappingPayloadTransformer` and execute lookup resolution.

- [x] **Step 4: Run test to verify it passes**
Run: `mvn test -pl application "-Dtest=FieldMappingPayloadTransformerTest"`
Expected: PASS

- [x] **Step 5: Commit**
```bash
git add application/src/main/java/com/cl2/integration/integration/transformation/field/ application/src/test/java/com/cl2/integration/integration/transformation/field/FieldMappingPayloadTransformerTest.java
git commit -m "feat(transformation): integrate value lookup in FieldMappingPayloadTransformer"
```

---

### Task 4: Integrate Custom `lookup(...)` Function into `JsltPayloadTransformer` and E2E Testing

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/integration/transformation/jslt/JsltPayloadTransformer.java`
- Create: `application/src/main/java/com/cl2/integration/integration/transformation/jslt/LookupJsltFunction.java`
- Test: `application/src/test/java/com/cl2/integration/integration/transformation/jslt/JsltPayloadTransformerTest.java`
- Test: `application/src/test/java/com/cl2/integration/integration/lookup/ValueLookupIntegrationTest.java`

**Interfaces:**
- JSLT expression: `lookup("CATALOG_CODE", .source_field, "DEFAULT_VALUE")`

- [ ] **Step 1: Write failing test in JsltPayloadTransformerTest**
Add test executing JSLT script calling `lookup("TIPO_VEHICULO", .tipo, "2")` verifying target translation and default fallback.

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn test -pl application "-Dtest=JsltPayloadTransformerTest"`
Expected: FAIL due to missing function.

- [ ] **Step 3: Implement `LookupJsltFunction.java` and register in `JsltPayloadTransformer.java`**
Register custom JSLT Function calling `ValueLookupService`. Create `ValueLookupIntegrationTest.java` for full end-to-end flow.

- [ ] **Step 4: Run test to verify it passes**
Run: `mvn test -pl application "-Dtest=JsltPayloadTransformerTest,ValueLookupIntegrationTest"`
Expected: PASS

- [ ] **Step 5: Run full test suite across all modules**
Run: `mvn test`
Expected: 100% BUILD SUCCESS

- [ ] **Step 6: Commit**
```bash
git add application/src/main/java/com/cl2/integration/integration/transformation/jslt/ application/src/test/java/com/cl2/integration/integration/transformation/jslt/JsltPayloadTransformerTest.java application/src/test/java/com/cl2/integration/integration/lookup/ValueLookupIntegrationTest.java
git commit -m "feat(transformation): add lookup function to JSLT and end-to-end integration tests"
```
