# Task 5 Report: Application use cases

## Status

Complete.

## Changed files

- `src/main/java/com/cl2/integration/application/IntegrationProfileService.java`
- `src/main/java/com/cl2/integration/application/IntegrationProfileView.java`
- `src/main/java/com/cl2/integration/application/command/CreateIntegrationProfileCommand.java`
- `src/main/java/com/cl2/integration/application/command/UpdateIntegrationProfileCommand.java`
- `src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java`

## Commit

- `daf8bca59610ebba3434340db2f584dbe157cfed feat: add integration profile use cases`

## TDD and test evidence

1. `mvn test -Dtest=IntegrationProfileServiceTest` initially could not resolve Maven dependencies in the sandbox. The network-enabled retry reached test compilation and failed as expected because the service, view, and commands did not exist.
2. After the initial implementation, `mvn test -Dtest=IntegrationProfileServiceTest` passed: 8 tests, 0 failures, 0 errors.
3. A review-driven regression test for updating an active profile to a duplicate active domain/source failed as expected: 9 tests, 1 failure, with no exception thrown.
4. After the targeted uniqueness safeguard, `mvn test -Dtest=IntegrationProfileServiceTest` passed: 9 tests, 0 failures, 0 errors, 0 skipped.

## Self-review

- All required use cases are present: create, list, get, update, and deactivate.
- Write operations are transactional; reads are marked read-only.
- The service passes the supplied tenant ID to each tenant-scoped repository operation, and saved profiles retain that tenant ID.
- The fake repository tests tenant isolation, missing profiles, active-only list filtering, create duplication, and update duplication.
- The update duplicate check only queries when an active profile changes its domain/source, avoiding a false collision with itself.
- `git diff --check` reported no whitespace errors before commit.

## Concerns

- Structural command validation remains intentionally at the upcoming web boundary, as required; domain validation continues to enforce business invariants.
- The targeted unit suite does not require Docker. Docker-backed persistence verification remains outside this task.

## Fix round 1: Explicit tenant-scoped saves

### Finding addressed

`IntegrationProfileRepository.save` accepted only an `IntegrationProfile`, allowing write callers to omit the explicit tenant ID required by the global tenant-isolation contract.

### Changes

- Changed the port to `save(UUID tenantId, IntegrationProfile profile)`.
- Updated all application-service and persistence-test save calls to pass the use-case or fixture tenant explicitly.
- Updated the persistence adapter to reject a null tenant/profile and reject any tenant ID that differs from `profile.tenantId()` before persistence.
- Updated the service fake repository to enforce the same tenant/profile relationship.
- Added the persistence mismatch test: saving a profile with another tenant ID throws `IllegalArgumentException` and leaves the tenant's data empty.

### Commit

- `bea8374f3f55183c999be58339cecc02c9bd23c4 fix: scope integration profile saves by tenant`

### Verification

1. TDD red: after tests were changed to use `save(UUID, IntegrationProfile)`, compilation failed as expected because the port and adapter still exposed only `save(IntegrationProfile)`.
2. `mvn test-compile` passed, compiling the complete service, domain, and persistence test sources.
3. `mvn test "-Dtest=IntegrationProfileServiceTest,IntegrationProfileTest"` passed: 19 tests, 0 failures, 0 errors.
4. `mvn test "-Dtest=IntegrationProfileServiceTest,IntegrationProfilePersistenceAdapterTest,IntegrationProfileTest"` compiled all test sources and passed the service/domain tests, but the Testcontainers persistence class could not run because no Docker environment is available.

### Self-review and deferred finding

- Repository write calls in production now all supply `tenantId`; the adapter checks the supplied tenant before any persistence operation.
- The minor DTO enum-coupling finding is deferred as requested and was not changed in this fix round.
