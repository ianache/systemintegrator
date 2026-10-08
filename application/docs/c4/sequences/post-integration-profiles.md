---
okf_version: "0.2"
title: "POST /api/v1/integration-profiles"
c4_level: Sequence
endpoint: "POST /api/v1/integration-profiles"
operation: create
focus_id: IntegrationProfileController
focus_kind: participant
provenance: INFERRED_FROM_CONTROLLER
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/integration-profiles

INFERRED_FROM_CONTROLLER: no existe OpenAPI; el contrato se derivo de `IntegrationProfileController.create` y del codigo que invoca. Crea el perfil (201). El evento IntegrationProfileCreated se publica a Kafka tras el commit.

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

    Cli->>TF: POST /api/v1/integration-profiles (X-Tenant-ID, body JSON)
    alt X-Tenant-ID ausente
        TF-->>Cli: 400 TENANT_HEADER_MISSING
    else X-Tenant-ID no es UUID
        TF-->>Cli: 400 TENANT_HEADER_MALFORMED
    else header valido
        TF->>TF: TenantContext.set(tenantId)
        TF->>C: create(@Valid CreateIntegrationProfileRequest)
        alt Bean Validation falla (businessDomain/externalSource en blanco, syncDirection/sourceOfTruth nulos) o JSON ilegible
            C-->>EH: MethodArgumentNotValidException / HttpMessageNotReadableException
            EH-->>Cli: 400 VALIDATION_FAILED / BAD_REQUEST
        else request valido
            C->>C: configurationRequest().toDomain(objectMapper)
            C->>S: create(TenantContext.requireTenantId(), CreateIntegrationProfileCommand)
            S->>P: existsActive(tenantId, businessDomain, externalSource)
            P->>A: existsActive(...)
            A->>DB: SELECT exists (tenant, dominio, fuente, active=true)
            DB-->>A: boolean
            alt ya existe perfil activo
                S-->>EH: IntegrationProfileConflictException
                EH-->>Cli: 409 INTEGRATION_PROFILE_CONFLICT
            else no existe
                S->>D: IntegrationProfile.create(UUID.randomUUID(), ...) active=true, paused=false, version=0
                alt dominio invalido (blank)
                    D-->>EH: IllegalArgumentException
                    EH-->>Cli: 400 BAD_REQUEST
                end
                S->>P: save(tenantId, profile)
                P->>A: save(...)
                A->>DB: INSERT integration_profile (persist + flush)
                alt violacion de unicidad / integridad
                    A-->>EH: IntegrationProfileConflictException
                    EH-->>Cli: 409 INTEGRATION_PROFILE_CONFLICT
                end
                DB-->>A: fila persistida
                A-->>S: IntegrationProfile
                S->>EV: publishEvent(IntegrationProfileEvent "IntegrationProfileCreated")
                S-->>C: IntegrationProfileView
                C->>C: toResponse(view): SyncStateRepository.find(profileId) (integration_sync_state)
                C-->>Cli: 201 Created IntegrationProfileResponse
                Note over EV,K: Tras COMMIT (AFTER_COMMIT, fallbackExecution)
                EV->>L: publishAfterCommit(event)
                L->>K: IntegrationProfileEventPublisher.publish (key=profileId)
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
- [CreateIntegrationProfileRequest](../../../src/main/java/com/cl2/integration/adapter/in/web/dto/CreateIntegrationProfileRequest.java)
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

- Conflicto 409 por perfil activo duplicado (dominio+fuente) esta en IntegrationProfileService.create y, como respaldo, en el adaptador (DataIntegrityViolation).
- Quien produce 403 no existe en este modulo: no hay Spring Security en la app. Un 403 solo podria venir del gateway (fuera de alcance).
