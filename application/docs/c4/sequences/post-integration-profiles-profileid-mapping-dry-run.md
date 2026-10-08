---
okf_version: "0.2"
title: "POST /api/v1/integration-profiles/{profileId}/mapping/dry-run"
c4_level: Sequence
endpoint: "POST /api/v1/integration-profiles/{profileId}/mapping/dry-run"
operation: mappingDryRun
focus_id: IntegrationProfileController
focus_kind: participant
provenance: INFERRED_FROM_CONTROLLER
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/integration-profiles/{profileId}/mapping/dry-run

INFERRED_FROM_CONTROLLER: no existe OpenAPI; el contrato se derivo de `IntegrationProfileController.mappingDryRun` y del codigo que invoca. Ejecuta una transformacion de prueba con transformationJson sin persistir. Errores de transformacion van en el cuerpo con 200.

El foco (el controller) se marca con `★`.

```mermaid
sequenceDiagram
    autonumber
    actor Cli as Cliente HTTP
    participant TF as TenantFilter / TenantContext
    participant C as ★ IntegrationProfileController
    participant EH as ApiExceptionHandler
    participant M as MappingDryRunService
    participant S as IntegrationProfileService
    participant P as IntegrationProfileRepository (puerto)
    participant A as IntegrationProfilePersistenceAdapter
    participant DB as MySQL (integration_profile)
    participant T as TransformationService
    participant PT as PayloadTransformer (FIELD_MAPPING / JSLT / PASSTHROUGH)

    Cli->>TF: POST /api/v1/integration-profiles/{profileId}/mapping/dry-run (X-Tenant-ID, body payload + transformationJson)
    alt X-Tenant-ID ausente
        TF-->>Cli: 400 TENANT_HEADER_MISSING
    else X-Tenant-ID no es UUID
        TF-->>Cli: 400 TENANT_HEADER_MALFORMED
    else header valido
        TF->>TF: TenantContext.set(tenantId)
        TF->>C: mappingDryRun(profileId, MappingDryRunRequest)
        alt JSON ilegible o profileId no UUID
            C-->>EH: HttpMessageNotReadable / TypeMismatch
            EH-->>Cli: 400 BAD_REQUEST
        else request valido
            C->>M: run(TenantContext.requireTenantId(), profileId, payload, transformationJson)
            M->>S: get(tenantId, profileId)
            S->>P: findById(tenantId, profileId)
            P->>A: findById(...)
            A->>DB: SELECT por tenant_id + id
            alt no existe
                A-->>EH: IntegrationProfileNotFoundException
                EH-->>Cli: 404 INTEGRATION_PROFILE_NOT_FOUND
            else existe
                DB-->>A: fila
                A-->>M: IntegrationProfileView
                M->>M: arma configuracion temporal con transformationJson (no se persiste)
                M->>T: transform(payload, perfil temporal)
                T->>PT: transform(payload, configJson) segun engine detectado
                alt transformacion falla
                    PT-->>M: Exception
                    M-->>C: MappingDryRunResult.failure(message)
                    C-->>Cli: 200 OK MappingDryRunResult (fallo en cuerpo)
                else exito
                    PT-->>T: salida
                    T-->>M: String
                    M-->>C: MappingDryRunResult.success(output)
                    C-->>Cli: 200 OK MappingDryRunResult
                end
            end
        end
    end
    Note over M: Solo lectura. No guarda nada ni publica eventos
    TF->>TF: TenantContext.clear() (finally)
```

## Archivos fuente

- [IntegrationProfileController](../../../src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)
- [MappingDryRunRequest](../../../src/main/java/com/cl2/integration/adapter/in/web/dto/MappingDryRunRequest.java)
- [MappingDryRunService](../../../src/main/java/com/cl2/integration/integration/transformation/MappingDryRunService.java)
- [IntegrationProfileService](../../../src/main/java/com/cl2/integration/application/IntegrationProfileService.java)
- [TransformationService](../../../src/main/java/com/cl2/integration/integration/transformation/TransformationService.java)
- [IntegrationProfileRepository](../../../src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java)
- [IntegrationProfilePersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java)

## Procedencia

- EXTRACTED: rutas, codigos de estado (@ResponseStatus), flujo controller -> service -> puerto -> adaptador, eventos, mapeo de excepciones en ApiExceptionHandler y errores de TenantFilter (400).
- INFERRED: nombres de tabla MySQL (por migraciones Flyway `integration_profile`, `integration_sync_state`), codigos HTTP de errores sin handler dedicado, y comportamiento de borde marcado AMBIGUOUS.
- No se documenta 403: no hay Spring Security en la aplicacion.

## Notas y dudas

- MappingDryRunService.run no es @Transactional; la lectura del perfil usa la transaccion readOnly de IntegrationProfileService.get.
- Los PayloadTransformer concretos (FieldMapping, Jslt, Passthrough) se resumen en un participante.
