---
okf_version: "0.2"
title: "POST /api/v1/integration-profiles/{profileId}/pause"
c4_level: Sequence
endpoint: "POST /api/v1/integration-profiles/{profileId}/pause"
operation: pause
focus_id: IntegrationProfileController
focus_kind: participant
provenance: INFERRED_FROM_CONTROLLER
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/integration-profiles/{profileId}/pause

INFERRED_FROM_CONTROLLER: no existe OpenAPI; el contrato se derivo de `IntegrationProfileController.pause` y del codigo que invoca. Pausa el perfil (paused=true) y publica IntegrationProfilePaused.

El foco (el controller) se marca con `★`.

```mermaid
sequenceDiagram
    autonumber
    actor Cli as Cliente HTTP
    participant TF as TenantFilter / TenantContext
    participant C as ★ IntegrationProfileController
    participant EH as ApiExceptionHandler
    participant S as IntegrationProfileService
    participant D as IntegrationProfile (dominio)
    participant P as IntegrationProfileRepository (puerto)
    participant A as IntegrationProfilePersistenceAdapter
    participant DB as MySQL (integration_profile)
    participant EV as ApplicationEventPublisher
    participant L as IntegrationProfileEventListener
    participant K as Kafka (integration-profile.events)

    Cli->>TF: POST /api/v1/integration-profiles/{profileId}/pause (X-Tenant-ID)
    alt X-Tenant-ID ausente
        TF-->>Cli: 400 TENANT_HEADER_MISSING
    else X-Tenant-ID no es UUID
        TF-->>Cli: 400 TENANT_HEADER_MALFORMED
    else header valido
        TF->>TF: TenantContext.set(tenantId)
        TF->>C: pause(profileId)
        alt profileId no es UUID
            C-->>EH: MethodArgumentTypeMismatchException
            EH-->>Cli: 400 BAD_REQUEST
        else profileId valido
            C->>S: pause(TenantContext.requireTenantId(), profileId)
            S->>P: findById(tenantId, profileId)
            P->>A: findById(...)
            A->>DB: SELECT por tenant_id + id
            alt no existe
                A-->>EH: IntegrationProfileNotFoundException
                EH-->>Cli: 404 INTEGRATION_PROFILE_NOT_FOUND
            else existe
                DB-->>A: fila
                A-->>S: IntegrationProfile
                S->>D: profile.pause()
                Note over D: Si ya estaba pausado devuelve this (version sin cambio)
                D-->>S: IntegrationProfile paused=true, version+1
                S->>P: save(tenantId, paused)
                P->>A: save(...)
                A->>DB: UPDATE ... WHERE tenant_id, id, version = version-1
                alt 0 filas (version obsoleta) o violacion de integridad
                    A-->>EH: IntegrationProfileConflictException
                    EH-->>Cli: 409 INTEGRATION_PROFILE_CONFLICT (AMBIGUOUS: tambien en pausa repetida)
                else 1 fila
                    A-->>S: IntegrationProfile
                    S->>EV: publishEvent(IntegrationProfileEvent "IntegrationProfilePaused")
                    S-->>C: IntegrationProfileView
                    C->>C: toResponse(view): SyncStateRepository.find(profileId)
                    C-->>Cli: 200 OK IntegrationProfileResponse (paused=true)
                    Note over EV,K: Tras COMMIT (AFTER_COMMIT)
                    EV->>L: publishAfterCommit(event)
                    L->>K: IntegrationProfileEventPublisher.publish
                end
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
- [IntegrationProfile](../../../src/main/java/com/cl2/integration/domain/model/IntegrationProfile.java)
- [IntegrationProfileRepository](../../../src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java)
- [IntegrationProfilePersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java)
- [IntegrationProfileEventPublisher](../../../src/main/java/com/cl2/integration/integration/profile/IntegrationProfileEventPublisher.java)
- [IntegrationProfileEventListener](../../../src/main/java/com/cl2/integration/integration/profile/IntegrationProfileEventListener.java)
- [SyncStatePersistenceAdapter](../../../src/main/java/com/cl2/integration/integration/sync/SyncStatePersistenceAdapter.java)

## Procedencia

- EXTRACTED: rutas, codigos de estado (@ResponseStatus), flujo controller -> service -> puerto -> adaptador, eventos, mapeo de excepciones en ApiExceptionHandler y errores de TenantFilter (400).
- INFERRED: nombres de tabla MySQL (por migraciones Flyway `integration_profile`, `integration_sync_state`), codigos HTTP de errores sin handler dedicado, y comportamiento de borde marcado AMBIGUOUS.
- No se documenta 403: no hay Spring Security en la aplicacion.

## Notas y dudas

- AMBIGUOUS: si ya estaba pausado, pause() devuelve la misma instancia sin incrementar version; save() usa version-1 como esperada, por lo que probablemente da 0 filas y 409. Verificar con una prueba.
