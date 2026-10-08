---
okf_version: "0.2"
c4_level: Sequence
endpoint: "GET /api/v1/flows/{flowId}/executions/{executionId}"
operation: "FlowController.getExecution"
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/flows/{flowId}/executions/{executionId}

Origen del contrato: INFERRED_FROM_CONTROLLER (no existe OpenAPI). El participante foco es `FlowController` (marcado con ★).

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant TF as TenantFilter
    participant C as FlowController ★
    participant M as FlowMetricsService
    participant ER as FlowExecutionPersistenceAdapter
    participant DB as MySQL (flow_execution, flow_execution_step)
    participant AH as ApiExceptionHandler

    Client->>TF: GET /api/v1/flows/{flowId}/executions/{executionId} (X-Tenant-ID)
    alt X-Tenant-ID ausente o no UUID
        TF-->>Client: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        TF->>C: TenantContext.set(tenantId)
        alt flowId o executionId no UUID
            C-->>AH: MethodArgumentTypeMismatchException
            AH-->>Client: 400 BAD_REQUEST
        else ids validos
            C->>M: getExecution(tenantId, flowId, executionId)
            M->>ER: findById(tenantId, flowId, executionId)
            ER->>DB: SELECT flow_execution WHERE tenant_id AND flow_id AND id
            alt no existe (o de otro tenant/flow)
                M-->>AH: FlowExecutionNotFoundException
                AH-->>Client: 404 FLOW_EXECUTION_NOT_FOUND
            else encontrada
                ER-->>M: FlowExecution
                M->>ER: findSteps(executionId)
                ER->>DB: SELECT flow_execution_step ORDER BY step_order
                DB-->>ER: filas
                ER-->>M: List FlowExecutionStep
                M-->>C: FlowExecutionWithSteps
                C-->>Client: 200 FlowExecutionDetailResponse
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
- [FlowExecutionRepository (puerto)](../../../src/main/java/com/cl2/integration/domain/port/FlowExecutionRepository.java)
- [FlowExecutionPersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/FlowExecutionPersistenceAdapter.java)

## Procedencia

- EXTRACTED: ruta, verbos, DTOs, llamadas controller -> servicio -> puerto -> adapter, consultas y excepciones/codigos HTTP, leidos directamente del codigo.
- INFERRED: la base de datos es MySQL (supuesto del proyecto; el codigo usa JPA/SQL nativo sin fijar el motor), nombres de tablas tomados de `@Table`, y la mencion de codigos 400/422 por tipo de excepcion.
- No existe respuesta 403 por tenant: el aislamiento es por filtrado `tenant_id` y un tenant ajeno recibe 404 (o 400 si falta/esta mal el header `X-Tenant-ID`).
- Sin Kafka ni sistemas externos en este endpoint.
