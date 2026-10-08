---
okf_version: "0.2"
title: "POST /api/v1/integration-profiles/{profileId}/extraction/dry-run"
c4_level: Sequence
endpoint: "POST /api/v1/integration-profiles/{profileId}/extraction/dry-run"
operation: extractionDryRun
focus_id: IntegrationProfileController
focus_kind: participant
provenance: INFERRED_FROM_CONTROLLER
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/integration-profiles/{profileId}/extraction/dry-run

INFERRED_FROM_CONTROLLER: no existe OpenAPI; el contrato se derivo de `IntegrationProfileController.extractionDryRun` y del codigo que invoca. Prueba la consulta de extraccion JDBC (maximo 20 filas de muestra). Fallos de configuracion o conexion van en el cuerpo con 200.

El foco (el controller) se marca con `★`.

```mermaid
sequenceDiagram
    autonumber
    actor Cli as Cliente HTTP
    participant TF as TenantFilter / TenantContext
    participant C as ★ IntegrationProfileController
    participant EH as ApiExceptionHandler
    participant X as ExtractionDryRunService
    participant S as IntegrationProfileService
    participant P as IntegrationProfileRepository (puerto)
    participant A as IntegrationProfilePersistenceAdapter
    participant DB as MySQL (integration_profile)
    participant SR as SecretResolver
    participant F as JdbcDataSourceFactory
    participant G as GenericJdbcAdapter
    participant EXT as Base de datos externa (JDBC)

    Cli->>TF: POST /api/v1/integration-profiles/{profileId}/extraction/dry-run (X-Tenant-ID)
    alt X-Tenant-ID ausente
        TF-->>Cli: 400 TENANT_HEADER_MISSING
    else X-Tenant-ID no es UUID
        TF-->>Cli: 400 TENANT_HEADER_MALFORMED
    else header valido
        TF->>TF: TenantContext.set(tenantId)
        TF->>C: extractionDryRun(profileId)
        alt profileId no es UUID
            C-->>EH: MethodArgumentTypeMismatchException
            EH-->>Cli: 400 BAD_REQUEST
        else profileId valido
            C->>X: run(TenantContext.requireTenantId(), profileId)
            X->>S: get(tenantId, profileId)
            S->>P: findById(tenantId, profileId)
            P->>A: findById(...)
            A->>DB: SELECT por tenant_id + id
            alt no existe
                A-->>EH: IntegrationProfileNotFoundException
                EH-->>Cli: 404 INTEGRATION_PROFILE_NOT_FOUND
            else existe
                DB-->>A: fila
                A-->>X: IntegrationProfileView
                alt protocolo distinto de JDBC / extractionConfig vacio / JSON invalido / query vacia
                    X-->>C: ExtractionDryRunResult.failure(mensaje)
                    C-->>Cli: 200 OK (fallo en cuerpo)
                else configuracion valida
                    X->>SR: resolve(credentialRef, tenantId)
                    SR-->>X: ResolvedSecret
                    X->>F: create(endpoint, secret) HikariDataSource temporal
                    X->>G: extract(jdbcTemplate, extractionConfig, Instant.EPOCH)
                    G->>EXT: ejecuta query de extraccion
                    alt error de secreto, conexion o SQL
                        EXT-->>X: Exception
                        X-->>C: ExtractionDryRunResult.failure(message)
                        C-->>Cli: 200 OK (fallo en cuerpo)
                    else exito
                        EXT-->>G: filas
                        G-->>X: List filas
                        X-->>C: ExtractionDryRunResult.success(muestra max 20, total)
                        C-->>Cli: 200 OK ExtractionDryRunResult
                    end
                end
            end
        end
    end
    Note over X: Transaccion readOnly. No usa ResilienceExecutor ni modifica watermark
    TF->>TF: TenantContext.clear() (finally)
```

## Archivos fuente

- [IntegrationProfileController](../../../src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)
- [ExtractionDryRunService](../../../src/main/java/com/cl2/integration/integration/extraction/ExtractionDryRunService.java)
- [IntegrationProfileService](../../../src/main/java/com/cl2/integration/application/IntegrationProfileService.java)
- [IntegrationProfileRepository](../../../src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java)
- [IntegrationProfilePersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java)

## Procedencia

- EXTRACTED: rutas, codigos de estado (@ResponseStatus), flujo controller -> service -> puerto -> adaptador, eventos, mapeo de excepciones en ApiExceptionHandler y errores de TenantFilter (400).
- INFERRED: nombres de tabla MySQL (por migraciones Flyway `integration_profile`, `integration_sync_state`), codigos HTTP de errores sin handler dedicado, y comportamiento de borde marcado AMBIGUOUS.
- No se documenta 403: no hay Spring Security en la aplicacion.

## Notas y dudas

- Clases auxiliares (SecretResolver, JdbcDataSourceFactory, GenericJdbcAdapter) se muestran como participantes sin detallar su interior.
