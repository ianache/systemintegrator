# Task 6 Report: Expose and Verify the REST API

## Status

Implemented and committed the Integration Profile REST adapter.

## Changed Files

- `src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java`
- `src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java`
- `src/main/java/com/cl2/integration/adapter/in/web/dto/CreateIntegrationProfileRequest.java`
- `src/main/java/com/cl2/integration/adapter/in/web/dto/UpdateIntegrationProfileRequest.java`
- `src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileResponse.java`
- `src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java`

## Commit

- `0318006041c8a578d904030356dcc0cc937c0b4a feat: expose integration profile REST API`

## Test Evidence

1. `mvn test -Dtest=IntegrationProfileControllerTest` (red): failed at test compilation because `IntegrationProfileController` did not exist.
2. `mvn test -Dtest=IntegrationProfileControllerTest` (red refinement): 11 tests ran; the invalid-body contract failed because the implementation returned `BAD_REQUEST` instead of `VALIDATION_FAILED`.
3. `mvn test -Dtest=IntegrationProfileControllerTest` (green): passed — 11 tests, 0 failures, 0 errors, 0 skipped.

The passing MockMvc suite covers create, absent and malformed tenant headers, validation, list with `activeOnly`, get, update, logical delete, not found, conflict, and cross-tenant `404` behavior.

## Self-Review

- Every controller operation obtains its tenant only through `TenantContext.requireTenantId()`.
- Request DTOs contain no `tenantId`; the create test submits a conflicting body `tenantId` and verifies the header tenant reaches the application service.
- Web responses map application views to DTOs; JPA entities are not used or exposed.
- Validation, not-found, conflict, malformed payload/type, tenant-context, and unexpected failures have `ProblemDetail` responses with a stable `errorCode` and a `correlationId`; unexpected responses do not include exception details or stack traces.
- `git diff --cached --check` passed before the feature commit.

## Concerns

- The pre-existing `TenantFilter` returns `sendError(400)` for missing or malformed `X-Tenant-ID` before controller advice runs. This correctly rejects requests before the controller, but those two filter-originated responses do not currently carry the `ProblemDetail` `errorCode` and `correlationId` used by controller-originated errors. Updating it would touch the Task 2 file, outside this task's permitted file scope.
- Mockito's runtime inline mock-maker emits JDK dynamic-agent warnings during the web test run; the tests pass, but the warning should be addressed centrally when the build config is next updated.

## Fix Round 1

### Changes

- Added `ApiProblemDetailFactory` so the controller advice and tenant filter construct the same `ProblemDetail` metadata contract.
- Updated `TenantFilter` to serialize `application/problem+json` before controller execution with `TENANT_HEADER_MISSING` or `TENANT_HEADER_MALFORMED` plus the incoming or generated `correlationId`; its `finally` cleanup is unchanged.
- Changed `UpdateIntegrationProfileRequest.expectedVersion` to `@NotNull @PositiveOrZero Long`, which rejects omitted and negative values before the controller maps the update command.
- Extended the MockMvc contract tests to assert tenant-boundary metadata and missing/negative version validation. Request DTOs still contain no tenant ID.

### Commit

- `baf65e025ff7fa0ec5751774852c42c215bdc625 fix: standardize tenant boundary errors`

### Test Evidence

1. `mvn test -Dtest=IntegrationProfileControllerTest` (red): 13 tests ran with four expected failures: missing and malformed tenant headers had no JSON metadata, while omitted and negative versions reached the service and produced `500`.
2. `mvn test -Dtest=IntegrationProfileControllerTest` (green): passed — 13 tests, 0 failures, 0 errors, 0 skipped.
3. `mvn test '-Dtest=IntegrationProfileControllerTest,TenantFilterTest'`: the web suite passed 13/13 and test compilation succeeded, but `TenantFilterTest` could not initialize its existing full Spring context because the local MySQL/Flyway datasource was unavailable.

### Self-Review

- The filter still rejects invalid tenant input before a controller is selected and clears `TenantContext` through the existing `finally` block.
- Both filter-originated and controller-advice responses now carry `errorCode` and `correlationId`; MockMvc asserts the filter response metadata and caller-supplied correlation IDs.
- Validation occurs before `Long` is unboxed for `UpdateIntegrationProfileCommand`, so a missing version cannot default to `0`.

### Remaining Concern

- The pre-existing `TenantFilterTest` uses a full application context and depends on a running local MySQL instance; it cannot run in this environment without the Task 7 test-database setup. The focused MockMvc suite covers the changed filter behavior without that dependency.
