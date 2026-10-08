---
okf_version: "0.2"
c4_level: Sequence
endpoint: "POST /api/v1/flows"
operation: "FlowController.create"
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/flows

Origen del contrato: INFERRED_FROM_CONTROLLER (no existe OpenAPI). El participante foco es `FlowController` (marcado con ★).

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant TF as TenantFilter
    participant C as FlowController ★
    participant S as FlowService
    participant R as FlowPersistenceAdapter
    participant DB as MySQL (tabla flow)
    participant AH as ApiExceptionHandler

    Client->>TF: POST /api/v1/flows (X-Tenant-ID, body code+name)
    alt X-Tenant-ID ausente o no UUID
        TF-->>Client: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        TF->>C: TenantContext.set(tenantId)
        C->>C: @Valid CreateFlowRequest (code, name @NotBlank)
        alt validacion falla
            C-->>AH: MethodArgumentNotValidException
            AH-->>Client: 400 VALIDATION_FAILED
        else body valido
            C->>S: create(tenantId, CreateFlowCommand)
            S->>R: existsActive(tenantId, code)
            R->>DB: SELECT exists (tenant_id, code, archived=false)
            DB-->>R: boolean
            alt ya existe flow activo con ese code
                S-->>AH: FlowConflictException
                AH-->>Client: 409 FLOW_CONFLICT
            else code libre
                S->>S: Flow.create(UUID, tenantId, code, name) version=0
                S->>R: save(tenantId, flow)
                R->>DB: INSERT flow (persist + flush)
                alt violacion de integridad (carrera)
                    R-->>AH: FlowConflictException
                    AH-->>Client: 409 FLOW_CONFLICT
                else ok
                    DB-->>R: fila
                    R-->>S: Flow
                    S-->>C: FlowView
                    C-->>Client: 201 FlowResponse
                end
            end
        end
    end
```

## Archivos fuente

- [FlowController](../../../src/main/java/com/cl2/integration/adapter/in/web/FlowController.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [FlowService](../../../src/main/java/com/cl2/integration/application/FlowService.java)
- [Flow (dominio)](../../../src/main/java/com/cl2/integration/domain/model/Flow.java)
- [FlowRepository (puerto)](../../../src/main/java/com/cl2/integration/domain/port/FlowRepository.java)
- [FlowPersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapter.java)
- [DTOs web](../../../src/main/java/com/cl2/integration/adapter/in/web/dto/)

## Procedencia

- EXTRACTED: ruta, verbos, DTOs, llamadas controller -> servicio -> puerto -> adapter, consultas y excepciones/codigos HTTP, leidos directamente del codigo.
- INFERRED: la base de datos es MySQL (supuesto del proyecto; el codigo usa JPA/SQL nativo sin fijar el motor), nombres de tablas tomados de `@Table`, y la mencion de codigos 400/422 por tipo de excepcion.
- No existe respuesta 403 por tenant: el aislamiento es por filtrado `tenant_id` y un tenant ajeno recibe 404 (o 400 si falta/esta mal el header `X-Tenant-ID`).
- Sin Kafka ni sistemas externos en este endpoint.
