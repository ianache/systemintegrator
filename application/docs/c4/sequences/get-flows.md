---
okf_version: "0.2"
c4_level: Sequence
endpoint: "GET /api/v1/flows"
operation: "FlowController.list"
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/flows

Origen del contrato: INFERRED_FROM_CONTROLLER (no existe OpenAPI). El participante foco es `FlowController` (marcado con ★).

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant TF as TenantFilter
    participant C as FlowController ★
    participant S as FlowService
    participant M as FlowMetricsService
    participant R as FlowPersistenceAdapter
    participant ER as FlowExecutionPersistenceAdapter
    participant DB as MySQL (flow, flow_execution)
    participant AH as ApiExceptionHandler

    Client->>TF: GET /api/v1/flows?activeOnly=true (X-Tenant-ID)
    alt X-Tenant-ID ausente o no UUID
        TF-->>Client: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        TF->>C: TenantContext.set(tenantId)
        alt activeOnly no es boolean
            C-->>AH: MethodArgumentTypeMismatchException
            AH-->>Client: 400 BAD_REQUEST
        else parametros validos
            C->>S: list(tenantId, activeOnly)
            S->>R: findAll(tenantId, activeOnly)
            R->>DB: SELECT flow WHERE tenant_id [AND archived=false] ORDER BY created_at DESC
            DB-->>R: filas
            R-->>S: List Flow
            S-->>C: List FlowView (conteo de nodos del draftGraph)
            loop por cada flow (N+1)
                C->>M: rowMetrics(tenantId, flowId)
                M->>ER: executionMetricsForFlow(tenantId, flowId, now-24h)
                ER->>DB: COUNT / COUNT FAILURE / p95 duration_ms (flow_execution)
                DB-->>ER: agregados
                ER-->>M: FlowMetricsSummary
                M-->>C: FlowMetricsSummary
            end
            C-->>Client: 200 List FlowSummaryResponse
        end
    end
```

## Archivos fuente

- [FlowController](../../../src/main/java/com/cl2/integration/adapter/in/web/FlowController.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [FlowService](../../../src/main/java/com/cl2/integration/application/FlowService.java)
- [FlowMetricsService](../../../src/main/java/com/cl2/integration/application/FlowMetricsService.java)
- [FlowRepository (puerto)](../../../src/main/java/com/cl2/integration/domain/port/FlowRepository.java)
- [FlowPersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/FlowPersistenceAdapter.java)
- [FlowExecutionRepository (puerto)](../../../src/main/java/com/cl2/integration/domain/port/FlowExecutionRepository.java)
- [FlowExecutionPersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/FlowExecutionPersistenceAdapter.java)

## Procedencia

- EXTRACTED: ruta, verbos, DTOs, llamadas controller -> servicio -> puerto -> adapter, consultas y excepciones/codigos HTTP, leidos directamente del codigo.
- INFERRED: la base de datos es MySQL (supuesto del proyecto; el codigo usa JPA/SQL nativo sin fijar el motor), nombres de tablas tomados de `@Table`, y la mencion de codigos 400/422 por tipo de excepcion.
- No existe respuesta 403 por tenant: el aislamiento es por filtrado `tenant_id` y un tenant ajeno recibe 404 (o 400 si falta/esta mal el header `X-Tenant-ID`).
- Sin Kafka ni sistemas externos en este endpoint.
