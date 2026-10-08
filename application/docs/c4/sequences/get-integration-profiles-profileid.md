---
okf_version: "0.2"
title: "GET /api/v1/integration-profiles/{profileId}"
c4_level: Sequence
endpoint: "GET /api/v1/integration-profiles/{profileId}"
operation: get
focus_id: IntegrationProfileController
focus_kind: participant
provenance: INFERRED_FROM_CONTROLLER
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/integration-profiles/{profileId}

INFERRED_FROM_CONTROLLER: no existe OpenAPI; el contrato se derivo de `IntegrationProfileController.get` y del codigo que invoca. Obtiene un perfil por id dentro del tenant. Otro tenant se ve como 404.

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

    Cli->>TF: GET /api/v1/integration-profiles/{profileId} (X-Tenant-ID)
    alt X-Tenant-ID ausente
        TF-->>Cli: 400 TENANT_HEADER_MISSING
    else X-Tenant-ID no es UUID
        TF-->>Cli: 400 TENANT_HEADER_MALFORMED
    else header valido
        TF->>TF: TenantContext.set(tenantId)
        TF->>C: get(profileId)
        alt profileId no es UUID
            C-->>EH: MethodArgumentTypeMismatchException
            EH-->>Cli: 400 BAD_REQUEST
        else profileId valido
            C->>S: get(TenantContext.requireTenantId(), profileId)
            S->>P: findById(tenantId, profileId)
            P->>A: findById(...)
            A->>DB: SELECT por tenant_id + id
            alt no existe (o pertenece a otro tenant)
                A-->>EH: IntegrationProfileNotFoundException
                EH-->>Cli: 404 INTEGRATION_PROFILE_NOT_FOUND
            else existe
                DB-->>A: fila
                A-->>S: IntegrationProfile
                S-->>C: IntegrationProfileView
                C->>SR: find(profileId)
                SR->>DS: SELECT por profile_id
                DS-->>C: Optional SyncState
                C-->>Cli: 200 OK IntegrationProfileResponse
            end
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

- Sin dudas adicionales.
