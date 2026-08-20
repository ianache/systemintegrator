# Spec: On-Demand Integration Profile Sync Endpoint

## 1. Context & Problem Statement
Currently, integration profile sync jobs (e.g., JDBC extraction from SAP HANA) are executed exclusively by the periodic background scheduler (`IntegrationSyncScheduler`) based on each profile's cron expression policy.

There is a need to manually trigger/force the synchronization of an integration profile on demand through an authenticated REST API endpoint without waiting for the scheduled cron trigger.

## 2. Goals & Non-Goals
- **Goals**:
  - Provide a REST endpoint `POST /api/v1/integration-profiles/{profileId}/sync` to trigger immediate synchronization.
  - Return `202 Accepted` asynchronously with tracking metadata (`profileId`, `status: "TRIGGERED"`, `triggeredAt`).
  - Guarantee multi-tenant boundary checks (the profile must exist, belong to the authenticated `tenantId`, and be active).
  - Prevent concurrent conflicting executions using the existing distributed lock (`ShedLock` with key `sync:<profileId>`).
  - Maintain consistent state recording in `integration_sync_state` and outbox events generation.
- **Non-Goals**:
  - Replacing the scheduled cron runner.
  - Making the HTTP request synchronous or waiting for external database extraction completion.

## 3. Architecture & Design

### 3.1 REST Endpoint
- **HTTP Method & Path**: `POST /api/v1/integration-profiles/{profileId}/sync`
- **Headers**:
  - `Authorization: Bearer <JWT>` (in Gateway / QA-E2E) or `X-Tenant-ID: <UUID>` (direct app communication).
- **Responses**:
  - `202 Accepted`: Synchronization successfully queued/dispatched.
  - `404 Not Found`: Profile does not exist or does not belong to the caller tenant.
  - `409 Conflict`: Profile is inactive (`active: false`).

#### Response Payload (202 Accepted)
```json
{
  "profileId": "b50c7266-6b30-475b-9057-d706f4ba24a8",
  "status": "TRIGGERED",
  "triggeredAt": "2026-08-19T22:15:00.000Z"
}
```

### 3.2 Component Responsibilities
1. **`IntegrationProfileController`**:
   - Exposes `@PostMapping("/{profileId}/sync")`.
   - Extracts `tenantId` from `TenantContext.requireTenantId()`.
   - Calls `IntegrationSyncService.triggerSync(tenantId, profileId)`.
   - Returns `TriggerSyncResponse` with status `HttpStatus.ACCEPTED`.
2. **`IntegrationSyncService`**:
   - Loads the profile from `IntegrationProfileRepository`.
   - Verifies tenant ownership and that the profile is active.
   - Dispatches the task asynchronously to `integrationSyncExecutor` wrapped in `LockingTaskExecutor` using `LockConfiguration("sync:" + profile.id(), lockAtMost, lockAtLeast)`.
   - Executes `IntegrationSyncOrchestrator.run(profile)`.
3. **`IntegrationSyncScheduler`**:
   - Refactored to delegate dispatching tasks to `IntegrationSyncService` for unified lock handling and execution.

### 3.3 Concurrency & Lock Handling
- Distributed lock key: `sync:<profileId>`.
- If a sync run is already in progress (either scheduled by cron or triggered manually by another request), `LockingTaskExecutor` safely skips execution without crashing or causing race conditions.

## 4. Test Strategy
- **Unit Tests**:
  - `IntegrationProfileControllerTest`: Verifies HTTP 202 Accepted response, 404 for non-existing profiles, and proper DTO serialization.
  - `IntegrationSyncServiceTest`: Verifies profile validation (tenant check, active status check) and async execution with lock configuration.
- **Integration Tests**:
  - Verify manual trigger executes extraction, writes outbox events, and updates `integration_sync_state`.
