---
okf_version: "0.2"
c4_level: Sequence
endpoint: "POST /api/v1/flows/{flowId}/versions/publish"
operation: "FlowController.publish"
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/flows/{flowId}/versions/publish

Origen del contrato: INFERRED_FROM_CONTROLLER (no existe OpenAPI). El participante foco es `FlowController` (marcado con ★).

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant TF as TenantFilter
    participant C as FlowController ★
    participant S as FlowService
    participant R as FlowPersistenceAdapter
    participant VR as FlowVersionPersistenceAdapter
    participant DB as MySQL (flow, flow_version)
    participant AH as ApiExceptionHandler

    Client->>TF: POST /api/v1/flows/{flowId}/versions/publish (X-Tenant-ID)
    alt X-Tenant-ID ausente o no UUID
        TF-->>Client: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        TF->>C: TenantContext.set(tenantId)
        C->>C: publishedBy = principal?.getName() ?: "unknown"
        C->>S: publish(tenantId, flowId, publishedBy)
        S->>R: findById(tenantId, flowId)
        R->>DB: SELECT flow WHERE tenant_id AND id
        alt flow no existe / otro tenant
            R-->>AH: FlowNotFoundException
            AH-->>Client: 404 FLOW_NOT_FOUND
        else existe
            R-->>S: Flow
            alt draftGraph nulo o vacio
                S-->>AH: FlowNotPublishableException
                AH-->>Client: 422 FLOW_NOT_PUBLISHABLE
            else draft con contenido
                S->>VR: findActiveByFlowId(tenantId, flowId)
                VR->>DB: SELECT flow_version WHERE state=ACTIVE
                opt existe version ACTIVE
                    S->>VR: save(active.withState(PUBLISHED))
                    VR->>DB: UPDATE flow_version SET state=PUBLISHED
                end
                S->>VR: nextVersionNumber(tenantId, flowId)
                VR->>DB: COUNT flow_version (+1)
                S->>VR: save(FlowVersion.publish(... graph=draftGraph, publishedBy))
                VR->>DB: INSERT flow_version (state ACTIVE)
                S->>R: save(tenantId, flow.withActiveVersion(n))
                R->>DB: UPDATE flow SET active_version_number, version+1 WHERE version=expected
                alt 0 filas (version obsoleta) o violacion de integridad
                    R-->>AH: FlowConflictException
                    AH-->>Client: 409 FLOW_CONFLICT (rollback de la transaccion)
                else ok
                    S-->>C: FlowVersionView
                    C-->>Client: 201 FlowVersionResponse
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
- [FlowVersion (dominio)](../../../src/main/java/com/cl2/integration/domain/model/FlowVersion.java)
- [FlowRepository (puerto)](../../../src/main/java/com/cl2/integration/domain/port/FlowRepository.java)
- [FlowVersionRepository (puerto)](../../../src/main/java/com/cl2/integration/domain/port/FlowVersionRepository.java)
- [FlowPersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapter.java)
- [FlowVersionPersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/FlowVersionPersistenceAdapter.java)

## Procedencia

- EXTRACTED: ruta, verbos, DTOs, llamadas controller -> servicio -> puerto -> adapter, consultas y excepciones/codigos HTTP, leidos directamente del codigo.
- INFERRED: la base de datos es MySQL (supuesto del proyecto; el codigo usa JPA/SQL nativo sin fijar el motor), nombres de tablas tomados de `@Table`, y la mencion de codigos 400/422 por tipo de excepcion.
- No existe respuesta 403 por tenant: el aislamiento es por filtrado `tenant_id` y un tenant ajeno recibe 404 (o 400 si falta/esta mal el header `X-Tenant-ID`).
- Sin Kafka ni sistemas externos en este endpoint.
