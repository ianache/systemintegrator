---
okf_version: "0.2"
c4_level: Sequence
endpoint: "GET /api/v1/flows/{flowId}/executions"
operation: "FlowController.listExecutions"
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/flows/{flowId}/executions

Origen del contrato: INFERRED_FROM_CONTROLLER (no existe OpenAPI). El participante foco es `FlowController` (marcado con ★).

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant TF as TenantFilter
    participant C as FlowController ★
    participant M as FlowMetricsService
    participant R as FlowPersistenceAdapter
    participant ER as FlowExecutionPersistenceAdapter
    participant DB as MySQL (flow, flow_execution)
    participant AH as ApiExceptionHandler

    Client->>TF: GET /api/v1/flows/{flowId}/executions (X-Tenant-ID)
    alt X-Tenant-ID ausente o no UUID
        TF-->>Client: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        TF->>C: TenantContext.set(tenantId)
        alt flowId no es UUID
            C-->>AH: MethodArgumentTypeMismatchException
            AH-->>Client: 400 BAD_REQUEST
        else flowId valido
            C->>M: listExecutions(tenantId, flowId)
            M->>R: findById(tenantId, flowId)
            R->>DB: SELECT flow WHERE tenant_id AND id
            alt flow no existe / otro tenant
                R-->>AH: FlowNotFoundException
                AH-->>Client: 404 FLOW_NOT_FOUND
            else existe
                M->>ER: findByFlow(tenantId, flowId)
                ER->>DB: SELECT flow_execution ORDER BY started_at DESC
                DB-->>ER: filas
                ER-->>M: List FlowExecution
                M-->>C: List FlowExecution
                C-->>Client: 200 List FlowExecutionResponse
            end
        end
    end
```

## Archivos fuente

- [FlowController](../../../src/main/java/com/cl2/integration/adapter/in/web/FlowController.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [FlowMetricsService](../../../src/main/java/com/cl2/integration/application/FlowMetricsService.java)
- [FlowRepository (puerto)](../../../src/main/java/com/cl2/integration/domain/port/FlowRepository.java)
- [FlowExecutionRepository (puerto)](../../../src/main/java/com/cl2/integration/domain/port/FlowExecutionRepository.java)
- [FlowPersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapter.java)
- [FlowExecutionPersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/FlowExecutionPersistenceAdapter.java)

## Procedencia

- EXTRACTED: ruta, verbos, DTOs, llamadas controller -> servicio -> puerto -> adapter, consultas y excepciones/codigos HTTP, leidos directamente del codigo.
- INFERRED: la base de datos es MySQL (supuesto del proyecto; el codigo usa JPA/SQL nativo sin fijar el motor), nombres de tablas tomados de `@Table`, y la mencion de codigos 400/422 por tipo de excepcion.
- No existe respuesta 403 por tenant: el aislamiento es por filtrado `tenant_id` y un tenant ajeno recibe 404 (o 400 si falta/esta mal el header `X-Tenant-ID`).
- Sin Kafka ni sistemas externos en este endpoint.
