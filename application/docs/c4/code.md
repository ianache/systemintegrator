---
okf_version: "0.2"
title: C4 Code - IntegrationProfileController
c4_level: Code
focus_id: IntegrationProfileController
focus_kind: code
focus_highlight: dark-red-700-border
status: REQUIRES_REVIEW
human-reviewed: false
---

# Code

Fuentes: [IntegrationProfileController.java](../../src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java), [IntegrationProfileService.java](../../src/main/java/com/cl2/integration/application/IntegrationProfileService.java).

El diagrama omite `pause`, `resume`, `delete` y los dry-runs del controller, y `IntegrationProfilePersistenceAdapter → IntegrationProfileRepository` es una relación por convención de nombres (`INFERRED`).

```mermaid
classDiagram
    class IntegrationProfileController {
        +create(request) IntegrationProfileResponse
        +triggerSync(profileId) TriggerSyncResponse
        +list(activeOnly) List
        +get(profileId) IntegrationProfileResponse
        +update(profileId, request) IntegrationProfileResponse
    }
    class IntegrationProfileService {
        +create(tenantId, command) IntegrationProfileView
        +list(tenantId, activeOnly) List
        +get(tenantId, profileId) IntegrationProfileView
        +update(tenantId, profileId, command) IntegrationProfileView
        +deactivate(tenantId, profileId) void
        +pause(tenantId, profileId) IntegrationProfileView
        +resume(tenantId, profileId) IntegrationProfileView
    }
    class IntegrationProfileRepository { <<interface>> +existsActive() +save() +findById() +findAll() }
    class IntegrationProfilePersistenceAdapter
    class IntegrationSyncService
    class TenantContext { +requireTenantId() UUID }
    class ApplicationEventPublisher { <<interface>> }
    IntegrationProfileController --> IntegrationProfileService
    IntegrationProfileController --> IntegrationSyncService
    IntegrationProfileController --> TenantContext
    IntegrationProfileService --> IntegrationProfileRepository
    IntegrationProfileService --> ApplicationEventPublisher
    IntegrationProfileRepository <|.. IntegrationProfilePersistenceAdapter
    style IntegrationProfileController stroke:#B71C1C,stroke-width:4px
    style IntegrationProfileService stroke:#616161,stroke-width:2px
    style IntegrationProfileRepository stroke:#616161,stroke-width:2px
    style IntegrationProfilePersistenceAdapter stroke:#616161,stroke-width:2px
    style IntegrationSyncService stroke:#616161,stroke-width:2px
    style TenantContext stroke:#616161,stroke-width:2px
    style ApplicationEventPublisher stroke:#616161,stroke-width:2px
```
