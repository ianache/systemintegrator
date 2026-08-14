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
