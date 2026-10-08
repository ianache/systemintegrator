---
okf_version: "0.2"
c4_level: Sequence
endpoint: "GET /api/v1/vehicles"
operation: listVehicles
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/vehicles (INFERRED_FROM_CONTROLLER)

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant F as TenantFilter
    participant K as ★ VehicleController
    participant S as VehicleService
    participant P as VehiclePersistenceAdapter
    participant DB as MySQL vehicle

    C->>F: GET /api/v1/vehicles?activeOnly=true (X-Tenant-ID)
    alt header ausente o no UUID
        F-->>C: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        F->>F: TenantContext.set(tenantId)
        F->>K: list(activeOnly, default true)
        alt activeOnly no es booleano
            K-->>C: 400 (MethodArgumentTypeMismatchException, default de Spring)
        else ok
            K->>K: TenantContext.requireTenantId()
            K->>S: list(tenantId, activeOnly)
            Note over S: @Transactional(readOnly)
            S->>P: findAll(tenantId, activeOnly)
            alt activeOnly = true
                P->>DB: SELECT ... WHERE tenant_id AND active=true ORDER BY created_at DESC
            else activeOnly = false
                P->>DB: SELECT ... WHERE tenant_id ORDER BY created_at DESC
            end
            DB-->>P: filas
            P-->>S: List<Vehicle>
            S-->>K: List<Vehicle>
            K-->>C: 200 List<VehicleResponse> ([] si no hay)
        end
    end
    F->>F: TenantContext.clear() (finally)
```

## Archivos fuente

- [VehicleController](../../src/main/java/com/cl2/integration/vehicle/adapter/in/web/VehicleController.java)
- [VehicleService](../../src/main/java/com/cl2/integration/vehicle/application/VehicleService.java)
- [VehiclePersistenceAdapter](../../src/main/java/com/cl2/integration/vehicle/adapter/out/persistence/VehiclePersistenceAdapter.java)
- [SpringDataVehicleRepository](../../src/main/java/com/cl2/integration/vehicle/adapter/out/persistence/SpringDataVehicleRepository.java)
- [TenantFilter](../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)

## Procedencia

- EXTRACTED: dos variantes de consulta segun `activeOnly`, orden por `createdAt` descendente.
- INFERRED: 400 por tipo invalido (default de Spring). Sin Kafka/outbox. Sin 403/404.
- Marca: INFERRED_FROM_CONTROLLER (no hay OpenAPI).
