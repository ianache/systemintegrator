---
okf_version: "0.2"
c4_level: Sequence
endpoint: "DELETE /api/v1/flows/{flowId}"
operation: "FlowController.archive"
status: REQUIRES_REVIEW
human-reviewed: false
---

# DELETE /api/v1/flows/{flowId}

Origen del contrato: INFERRED_FROM_CONTROLLER (no existe OpenAPI). El participante foco es `FlowController` (marcado con ★).

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant TF as TenantFilter
    participant C as FlowController ★
    participant S as FlowService
    participant F as Flow (dominio)
    participant R as FlowPersistenceAdapter
    participant DB as MySQL (tabla flow)
    participant AH as ApiExceptionHandler

    Client->>TF: DELETE /api/v1/flows/{flowId} (X-Tenant-ID)
    alt X-Tenant-ID ausente o no UUID
        TF-->>Client: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        TF->>C: TenantContext.set(tenantId)
        alt flowId no es UUID
            C-->>AH: MethodArgumentTypeMismatchException
            AH-->>Client: 400 BAD_REQUEST
        else flowId valido
            C->>S: archive(tenantId, flowId)
            S->>R: findById(tenantId, flowId)
            R->>DB: SELECT flow WHERE tenant_id AND id
            alt flow no existe / otro tenant
                R-->>AH: FlowNotFoundException
                AH-->>Client: 404 FLOW_NOT_FOUND
            else existe
                R-->>S: Flow
                S->>F: archive() (soft delete; idempotente si ya archivado)
                F-->>S: Flow archived=true, version+1
                S->>R: save(tenantId, flow)
                R->>DB: UPDATE flow SET archived=true WHERE version=expected
                alt 0 filas (version obsoleta)
                    R-->>AH: FlowConflictException
                    AH-->>Client: 409 FLOW_CONFLICT
                else ok
                    S-->>C: void
                    C-->>Client: 204 No Content
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

## Procedencia

- EXTRACTED: ruta, verbos, DTOs, llamadas controller -> servicio -> puerto -> adapter, consultas y excepciones/codigos HTTP, leidos directamente del codigo.
- INFERRED: la base de datos es MySQL (supuesto del proyecto; el codigo usa JPA/SQL nativo sin fijar el motor), nombres de tablas tomados de `@Table`, y la mencion de codigos 400/422 por tipo de excepcion.
- No existe respuesta 403 por tenant: el aislamiento es por filtrado `tenant_id` y un tenant ajeno recibe 404 (o 400 si falta/esta mal el header `X-Tenant-ID`).
- Sin Kafka ni sistemas externos en este endpoint.
