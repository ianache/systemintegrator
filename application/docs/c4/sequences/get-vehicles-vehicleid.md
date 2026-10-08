---
okf_version: "0.2"
c4_level: Sequence
endpoint: "GET /api/v1/vehicles/{vehicleId}"
operation: getVehicle
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/vehicles/{vehicleId} (INFERRED_FROM_CONTROLLER)

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant F as TenantFilter
    participant K as ★ VehicleController
    participant S as VehicleService
    participant P as VehiclePersistenceAdapter
    participant DB as MySQL vehicle

    C->>F: GET /api/v1/vehicles/{vehicleId} (X-Tenant-ID)
    alt header ausente o no UUID
        F-->>C: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        F->>F: TenantContext.set(tenantId)
        F->>K: get(@PathVariable UUID vehicleId)
        alt vehicleId no es UUID
            K-->>C: 400 (MethodArgumentTypeMismatchException, default de Spring)
        else ok
            K->>K: TenantContext.requireTenantId()
            K->>S: get(tenantId, vehicleId)
            Note over S: @Transactional(readOnly)
            S->>P: findById(tenantId, vehicleId)
            P->>DB: SELECT WHERE tenant_id = ? AND id = ?
            alt no existe (o pertenece a otro tenant)
                DB-->>P: vacio
                P-->>C: IllegalArgumentException "Vehicle was not found" (ver AMBIGUOUS: 500 o 400, no 404)
            else existe
                DB-->>P: VehicleJpaEntity
                P-->>S: Vehicle (toDomain)
                S-->>K: Vehicle
                K-->>C: 200 VehicleResponse
            end
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

- EXTRACTED: `findByTenantIdAndId` + `orElseThrow(IllegalArgumentException)`.
- INFERRED: el status del "no encontrado" (el handler global no cubre este paquete). Un vehiculo de otro tenant se trata igual que uno inexistente (no 403).
- Marca: INFERRED_FROM_CONTROLLER (no hay OpenAPI).
