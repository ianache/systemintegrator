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
