---
okf_version: "0.2"
title: "POST /api/v1/integration-profiles/{profileId}/sync"
c4_level: Sequence
endpoint: "POST /api/v1/integration-profiles/{profileId}/sync"
operation: triggerSync
focus_id: IntegrationProfileController
focus_kind: participant
provenance: INFERRED_FROM_CONTROLLER
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/integration-profiles/{profileId}/sync

INFERRED_FROM_CONTROLLER: no existe OpenAPI; el contrato se derivo de `IntegrationProfileController.triggerSync` y del codigo que invoca. Dispara una sincronizacion asincrona (202). El resultado de la corrida no regresa al cliente.

El foco (el controller) se marca con `★`.

```mermaid
sequenceDiagram
    autonumber
    actor Cli as Cliente HTTP
    participant TF as TenantFilter / TenantContext
    participant C as ★ IntegrationProfileController
    participant EH as ApiExceptionHandler
    participant SS as IntegrationSyncService
    participant P as IntegrationProfileRepository (puerto)
    participant A as IntegrationProfilePersistenceAdapter
    participant DB as MySQL (integration_profile)
    participant EX as integrationSyncExecutor + ShedLock (LockingTaskExecutor)
    participant O as IntegrationSyncOrchestrator
    participant OB as OutboxRepository / SyncStateRepository (MySQL)

    Cli->>TF: POST /api/v1/integration-profiles/{profileId}/sync (X-Tenant-ID)
    alt X-Tenant-ID ausente
        TF-->>Cli: 400 TENANT_HEADER_MISSING
    else X-Tenant-ID no es UUID
        TF-->>Cli: 400 TENANT_HEADER_MALFORMED
    else header valido
        TF->>TF: TenantContext.set(tenantId)
        TF->>C: triggerSync(profileId)
        alt profileId no es UUID
            C-->>EH: MethodArgumentTypeMismatchException
            EH-->>Cli: 400 BAD_REQUEST
        else profileId valido
            C->>SS: triggerSync(TenantContext.requireTenantId(), profileId)
            SS->>P: findById(tenantId, profileId)
            P->>A: findById(...)
            A->>DB: SELECT por tenant_id + id
            alt perfil no existe para el tenant
                A-->>EH: IntegrationProfileNotFoundException
                EH-->>Cli: 404 INTEGRATION_PROFILE_NOT_FOUND
            else perfil inactivo
                SS-->>EH: IllegalStateException (no mapeada explicitamente)
                EH-->>Cli: 500 INTERNAL_ERROR (AMBIGUOUS: posible 409/422 esperado)
            else perfil activo
                DB-->>A: fila
                A-->>SS: IntegrationProfile
                SS->>EX: dispatch(profile): submit tarea con lock "sync:{profileId}"
                SS-->>C: void (sin esperar la ejecucion)
                C-->>Cli: 202 Accepted TriggerSyncResponse(status=TRIGGERED)
                Note over EX,OB: Asincrono, fuera del request HTTP
                EX->>O: run(profile) bajo lock ShedLock
                O->>OB: lee watermark, extrae (JDBC/REST), transforma, guarda outbox y upsert SyncState
                Note over EX: Fallos solo se registran en log, no vuelven al cliente
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
- [IntegrationSyncService](../../../src/main/java/com/cl2/integration/integration/sync/IntegrationSyncService.java)
- [IntegrationSyncOrchestrator](../../../src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java)
- [TriggerSyncResponse](../../../src/main/java/com/cl2/integration/adapter/in/web/dto/TriggerSyncResponse.java)
- [IntegrationProfileRepository](../../../src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java)
- [IntegrationProfilePersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java)

## Procedencia

- EXTRACTED: rutas, codigos de estado (@ResponseStatus), flujo controller -> service -> puerto -> adaptador, eventos, mapeo de excepciones en ApiExceptionHandler y errores de TenantFilter (400).
- INFERRED: nombres de tabla MySQL (por migraciones Flyway `integration_profile`, `integration_sync_state`), codigos HTTP de errores sin handler dedicado, y comportamiento de borde marcado AMBIGUOUS.
- No se documenta 403: no hay Spring Security en la aplicacion.

## Notas y dudas

- AMBIGUOUS: perfil inactivo lanza IllegalStateException; no hay handler dedicado, por lo que ApiExceptionHandler la resuelve como 500 INTERNAL_ERROR (inferido del codigo).
- AMBIGUOUS: triggerSync no verifica paused; un perfil pausado se despacha igual.
- El detalle interno de IntegrationSyncOrchestrator.run se resume en una sola flecha (fuera del foco).
