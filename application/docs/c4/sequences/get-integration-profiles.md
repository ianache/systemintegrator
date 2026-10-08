---
okf_version: "0.2"
title: "GET /api/v1/integration-profiles"
c4_level: Sequence
endpoint: "GET /api/v1/integration-profiles"
operation: list
focus_id: IntegrationProfileController
focus_kind: participant
provenance: INFERRED_FROM_CONTROLLER
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/integration-profiles

INFERRED_FROM_CONTROLLER: no existe OpenAPI; el contrato se derivo de `IntegrationProfileController.list` y del codigo que invoca. Lista perfiles del tenant; activeOnly=true por defecto. Cada elemento se enriquece con SyncState.

El foco (el controller) se marca con `★`.

```mermaid
sequenceDiagram
    autonumber
    actor Cli as Cliente HTTP
    participant TF as TenantFilter / TenantContext
    participant C as ★ IntegrationProfileController
    participant EH as ApiExceptionHandler
    participant S as IntegrationProfileService
    participant P as IntegrationProfileRepository (puerto)
    participant A as IntegrationProfilePersistenceAdapter
    participant DB as MySQL (integration_profile)
    participant SR as SyncStateRepository (SyncStatePersistenceAdapter)
    participant DS as MySQL (integration_sync_state)

    Cli->>TF: GET /api/v1/integration-profiles?activeOnly=true (X-Tenant-ID)
    alt X-Tenant-ID ausente
        TF-->>Cli: 400 TENANT_HEADER_MISSING
    else X-Tenant-ID no es UUID
        TF-->>Cli: 400 TENANT_HEADER_MALFORMED
    else header valido
        TF->>TF: TenantContext.set(tenantId)
        TF->>C: list(activeOnly, default true)
        alt activeOnly no es boolean
            C-->>EH: MethodArgumentTypeMismatchException
            EH-->>Cli: 400 BAD_REQUEST
        else parametro valido
            C->>S: list(TenantContext.requireTenantId(), activeOnly)
            S->>P: findAll(tenantId, activeOnly)
            P->>A: findAll(...)
            alt activeOnly = true
                A->>DB: SELECT ... WHERE tenant_id AND active ORDER BY created_at DESC
            else activeOnly = false
                A->>DB: SELECT ... WHERE tenant_id ORDER BY created_at DESC
            end
            DB-->>A: filas
            A-->>S: List IntegrationProfile
            S-->>C: List IntegrationProfileView
            loop por cada perfil (toResponse)
                C->>SR: find(profileId)
                SR->>DS: SELECT por profile_id
                DS-->>C: Optional SyncState
            end
            C-->>Cli: 200 OK List IntegrationProfileResponse (puede ser vacia)
        end
    end
    TF->>TF: TenantContext.clear() (finally)
```

## Archivos fuente

- [IntegrationProfileController](../../../src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)
- [IntegrationProfileService](../../../src/main/java/com/cl2/integration/application/IntegrationProfileService.java)
- [SyncStatePersistenceAdapter](../../../src/main/java/com/cl2/integration/integration/sync/SyncStatePersistenceAdapter.java)
- [IntegrationProfileRepository](../../../src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java)
- [IntegrationProfilePersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java)

## Procedencia

- EXTRACTED: rutas, codigos de estado (@ResponseStatus), flujo controller -> service -> puerto -> adaptador, eventos, mapeo de excepciones en ApiExceptionHandler y errores de TenantFilter (400).
- INFERRED: nombres de tabla MySQL (por migraciones Flyway `integration_profile`, `integration_sync_state`), codigos HTTP de errores sin handler dedicado, y comportamiento de borde marcado AMBIGUOUS.
- No se documenta 403: no hay Spring Security en la aplicacion.

## Notas y dudas

- Patron N+1: una lectura de integration_sync_state por perfil (toResponse).
