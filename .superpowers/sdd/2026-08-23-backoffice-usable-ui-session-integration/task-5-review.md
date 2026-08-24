# Task 5 review - read-only Integration Profiles MicroUI

## Verdict

**PASS WITH NOTES**

The Task 5 MicroUI now provides the required recovery action for an unavailable
response and retains the original read-only and federation-route contracts.

## Findings

### F1 - Resolved: the reviewed `direction` finding was based on the view, not the HTTP DTO

The original finding inspected `IntegrationProfileView` rather than the DTO used by
the HTTP endpoint. `application/src/main/java/com/cl2/integration/adapter/in/web/dto/
IntegrationProfileResponse.java` declares `SyncDirection syncDirection`; Jackson
therefore serializes the response as `syncDirection`. The frontend model, table, and
fixtures already use that exact field, so no frontend or BFF mapping is required.

**Status:** resolved by verification of the actual HTTP DTO; the original mismatch
finding was false.

**Evidence:** `IntegrationProfileResponse.syncDirection` and the existing
`IntegrationProfile.syncDirection` field agree.

### F2 - Resolved: unavailable state provides retry recovery

The unavailable branch now renders a native `Retry` button. Its click invokes the
component retry action, immediately returns the view to loading, and reissues the
profile request. Focused component coverage exercises the 502 → Retry → loading/new
request → empty-state path.

**Status:** resolved.

## Requirement checks

- Same-origin `/bff/api/v1/integration-profiles` service endpoint is present.
- Required model fields are declared and match the actual HTTP DTO field naming.
- Read-only accessible table, loading, populated, and empty states are present.
- 401/403 show a generic message and route to `/auth/login` without rendering the
  backend response body.
- 502 uses a generic unavailable message, does not render backend details, and exposes an accessible Retry action.
- The `''` federation route and `integration-mfe-loaded` marker are preserved.
- No mutation controls were introduced.

## Scope and verification

Review scope covers the Task 5 implementation, its focused retry coverage, and the
actual backend HTTP DTO. No test suite was run. `git diff --check` is run before the
requested commit.

## Return status

**Task 5 review complete - PASS WITH NOTES.**
