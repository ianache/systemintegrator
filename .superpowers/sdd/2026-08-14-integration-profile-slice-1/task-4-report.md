# Task 4 Report: MySQL schema and persistence adapter

## Changed files

- `pom.xml`
  - Added Spring Data JPA, Flyway MySQL support, MySQL Connector/J, and MySQL Testcontainers dependencies.
- `src/main/resources/application.yml`
  - Configured the datasource from `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` with local defaults, enabled Flyway, enabled Hibernate validation, disabled DDL creation, and set Hibernate JDBC time zone to UTC.
- `src/main/resources/db/migration/V1__create_integration_profile.sql`
  - Created the `integration_profile` table with BINARY(16) UUIDs, enum strings, microsecond UTC timestamps, optimistic version, tenant indexes, and a generated nullable key that allows one active profile per tenant/domain/source while retaining multiple inactive rows.
- `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntity.java`
  - Added a JPA entity separate from the domain model, including enum-string mappings, `@Version`, BINARY UUID storage, and timestamp mappings.
- `src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java`
  - Added tenant-scoped lookup, list, and active-existence queries.
- `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java`
  - Implemented the domain repository port with tenant-scoped persistence and duplicate/optimistic-lock conflict translation.
- `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java`
  - Added MySQL Testcontainers coverage for migration, save/read, tenant isolation, logical deactivation, and active uniqueness.

## Commit

- `761cabac3287a03e8a41704605d648b261063326` — `feat: persist tenant-scoped integration profiles`

## TDD and test commands

1. `mvn test -Dtest=IntegrationProfilePersistenceAdapterTest` before persistence dependencies and adapter implementation.
   - Failed as expected: the test could not resolve Flyway, JDBC, Testcontainers, or the missing adapter.
2. `mvn test -Dtest=IntegrationProfilePersistenceAdapterTest` after adding dependencies but before adapter implementation.
   - Failed as expected: `IntegrationProfilePersistenceAdapter` was absent.
3. `mvn test -Dtest=IntegrationProfilePersistenceAdapterTest` after implementation.
   - Production and test sources compiled successfully. The test did not execute because Testcontainers could not find a Docker environment (`docker` is absent from PATH and no daemon is configured).
4. `mvn test -DskipTests`.
   - Passed. Maven compiled 13 main and 5 test sources successfully; Surefire skipped execution as requested.
5. `git diff --check`.
   - Passed before staging the feature commit.

## Self-review

- Confirmed the schema stores UUID identifiers as `BINARY(16)`, uses enum strings, includes `version`, and declares UTC-capable `TIMESTAMP(6)` fields.
- Confirmed both required indexes are present and the generated nullable key makes the active-identity uniqueness rule MySQL-compatible while permitting inactive historical rows.
- Confirmed every adapter query includes `tenantId`: ID lookup, both list variants, and active existence check.
- Confirmed duplicate key and optimistic locking failures are translated to `IntegrationProfileConflictException`.
- Preserved the existing domain port contract: `findById` returns `Optional<IntegrationProfile>` and returns empty for an absent or cross-tenant ID.

## Concerns

- The MySQL Testcontainers suite remains unexecuted in this environment because no Docker CLI/daemon is available. It must be run on a Docker-capable worker.
- The Task 4 brief asks for absence to become `IntegrationProfileNotFoundException`, but the Task 3 repository port explicitly returns `Optional<IntegrationProfile>`. The adapter preserves the established port contract; if exception-on-absence is required, the port needs an approved contract change.

## Fix round 1

### Resolved findings

- Changed `IntegrationProfileRepository.findById` to return `IntegrationProfile`; an absent tenant-scoped row now raises `IntegrationProfileNotFoundException` in the adapter.
- Replaced reload-and-merge updates with a tenant-scoped JPQL update whose `WHERE` clause includes the caller's expected database version (`profile.version() - 1`). The statement increments the version atomically, and a zero-row result becomes `IntegrationProfileConflictException`.
- Added `IntegrationProfile.rehydrate` and made the JPA entity use it directly. This preserves persisted active state, version, `createdAt`, and `updatedAt` rather than replaying domain transitions. MySQL timestamp values are normalized to microsecond precision before persistence.
- Replaced the `JpaRepository` inheritance with the marker `Repository` plus explicit tenant-scoped methods and update query, preventing inherited unscoped `findById`, `findAll`, and similar operations.
- Added focused tests for rehydration timestamp fidelity, the scoped repository surface, not-found reads, timestamp round trips after update, and stale concurrent mutations.

### Root cause and self-review

- The original adapter reread the row before every save and allowed a caller's target version to equal either the reloaded version or the next version. Two mutations derived from version 0 could therefore both succeed: the second was treated as a valid version-1 update and became version 2.
- The original entity-to-domain mapper called `create`, `update`, and `deactivate`, which generated new timestamps and reconstructed state indirectly. Direct domain rehydration removes that behavior.
- Confirmed every repository lookup and update method requires a `UUID tenantId`, and the adapter performs no unscoped entity lookup.
- Confirmed the port and adapter now consistently represent lookup absence as `IntegrationProfileNotFoundException`.

### Fix-round tests

1. `mvn test -Dtest=IntegrationProfileTest` initially failed as expected because `IntegrationProfile.rehydrate` was absent; it passed after the minimal domain factory was added.
2. `mvn test -Dtest=IntegrationProfileJpaEntityTest` initially failed as expected because entity rehydration produced a new timestamp; it passed after direct rehydration was implemented.
3. `mvn test -Dtest=IntegrationProfileTest,IntegrationProfileJpaEntityTest,SpringDataIntegrationProfileRepositoryTest` passed with 12 tests, 0 failures, and 0 errors.
4. `mvn test -Dtest=IntegrationProfilePersistenceAdapterTest` compiled production and test sources but did not execute test methods because Testcontainers could not find a Docker environment.

### Remaining concern

- The database-backed evidence for stale-write rejection, timestamp round trips, duplicate-key translation, and the new not-found path remains unverified until this suite runs on a Docker-capable worker.
