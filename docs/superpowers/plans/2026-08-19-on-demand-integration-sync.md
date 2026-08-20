# On-Demand Integration Profile Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide an on-demand REST endpoint `POST /api/v1/integration-profiles/{profileId}/sync` returning HTTP 202 Accepted to immediately trigger asynchronous synchronization under multi-tenant isolation and distributed locking.

**Architecture:** Create an `IntegrationSyncService` encapsulating profile lookup, validation (tenant and active state), and asynchronous dispatch via `LockingTaskExecutor` (`ShedLock`) and `integrationSyncExecutor`. Expose the endpoint in `IntegrationProfileController` returning `TriggerSyncResponse`. Refactor `IntegrationSyncScheduler` to delegate execution to `IntegrationSyncService`.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring MVC, ShedLock, JUnit 5, AssertJ, MockMvc.

## Global Constraints
- Target endpoint: `POST /api/v1/integration-profiles/{profileId}/sync`
- Response status: `202 Accepted`
- Response body structure: `TriggerSyncResponse(UUID profileId, String status, Instant triggeredAt)`
- Distributed lock key: `"sync:" + profile.id()`

---

### Task 1: Create `TriggerSyncResponse` DTO and `IntegrationSyncService`

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/in/web/dto/TriggerSyncResponse.java`
- Create: `src/main/java/com/cl2/integration/integration/sync/IntegrationSyncService.java`
- Test: `src/test/java/com/cl2/integration/integration/sync/IntegrationSyncServiceTest.java`

- [ ] **Step 1: Write the failing unit tests for `IntegrationSyncService`**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `TriggerSyncResponse` and `IntegrationSyncService`**
- [ ] **Step 4: Run test to verify it passes**

---

### Task 2: Expose `POST /api/v1/integration-profiles/{profileId}/sync` in `IntegrationProfileController` and Refactor Scheduler

**Files:**
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java`
- Modify: `src/main/java/com/cl2/integration/integration/sync/IntegrationSyncScheduler.java`
- Test: `src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java`
- Test: `src/test/java/com/cl2/integration/integration/sync/IntegrationSyncSchedulerTest.java`

- [ ] **Step 1: Write the failing unit tests in `IntegrationProfileControllerTest`**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Update `IntegrationProfileController` and `IntegrationSyncScheduler`**
- [ ] **Step 4: Run test to verify all tests pass**

---

### Task 3: Build & Verification

**Files:**
- Test: `application/pom.xml`

- [ ] **Step 1: Run full Maven test suite**
- [ ] **Step 2: Verify Docker container build**
