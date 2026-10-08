---
okf_version: "0.2"
c4_level: Sequence
endpoint: "POST /api/v1/vehicles"
operation: createVehicle
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/vehicles (INFERRED_FROM_CONTROLLER)

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant F as TenantFilter
    participant K as ★ VehicleController
    participant S as VehicleService
    participant D as Vehicle (domain)
    participant P as VehiclePersistenceAdapter
    participant O as OutboxRepository
    participant DB as MySQL (vehicle, integration_outbox)
    participant R as OutboxRelayScheduler
    participant KP as KafkaOutboxPublisher
    participant KF as Kafka

    C->>F: POST /api/v1/vehicles (X-Tenant-ID, CreateVehicleRequest)
    alt header ausente o no UUID
        F-->>C: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        F->>F: TenantContext.set(tenantId)
        F->>K: create(@Valid CreateVehicleRequest)
        alt vin/brandCode/modelCode en blanco o modelYear fuera de 1886..3000
            K-->>C: 400 (MethodArgumentNotValidException)
        else body valido
            K->>K: TenantContext.requireTenantId()
            K->>S: create(tenantId, vin, brandCode, modelCode, modelYear)
            Note over S: @Transactional
            S->>P: existsByVin(tenantId, vin)
            P->>DB: SELECT exists (tenant_id, vin)
            DB-->>S: boolean
            alt VIN ya existe en el tenant
                S-->>C: IllegalArgumentException (ver AMBIGUOUS: 500 o 400, no 409)
            else VIN libre
                S->>D: Vehicle.create(randomUUID, tenantId, ...)
                D-->>S: Vehicle (active=true, version=0)
                S->>P: save(vehicle)
                P->>DB: INSERT vehicle
                DB-->>P: fila
                P-->>S: Vehicle
                S->>O: save(OutboxEvent.pending(tenant, id, "Vehicle", "vehicle.created", json VehicleEvent.created))
                O->>DB: INSERT integration_outbox (PENDING, misma tx)
                S-->>K: Vehicle
                K-->>C: 201 VehicleResponse
            end
        end
    end
    F->>F: TenantContext.clear() (finally)
    R-)DB: poll pendientes (asincrono, fixed-delay 1s)
    R->>KP: publish(entity)
    KP->>KF: send(topic o "integration.events", key=outboxId, headers X-Tenant-ID, X-Event-Type, X-Aggregate-ID)
    R->>DB: marca PUBLISHED, o reintento con backoff si falla
```

## Archivos fuente

- [VehicleController](../../src/main/java/com/cl2/integration/vehicle/adapter/in/web/VehicleController.java)
- [VehicleService](../../src/main/java/com/cl2/integration/vehicle/application/VehicleService.java)
- [VehicleEvent](../../src/main/java/com/cl2/integration/vehicle/application/VehicleEvent.java)
- [Vehicle](../../src/main/java/com/cl2/integration/vehicle/domain/Vehicle.java)
- [VehiclePersistenceAdapter](../../src/main/java/com/cl2/integration/vehicle/adapter/out/persistence/VehiclePersistenceAdapter.java)
- [OutboxRelayScheduler](../../src/main/java/com/cl2/integration/integration/outbox/OutboxRelayScheduler.java)
- [KafkaOutboxPublisher](../../src/main/java/com/cl2/integration/integration/outbox/KafkaOutboxPublisher.java)
- [TenantFilter](../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [V2__create_vehicle_outbox_inbox.sql](../../src/main/resources/db/migration/V2__create_vehicle_outbox_inbox.sql)

## Procedencia

- EXTRACTED: pre-chequeo `existsByVin`, insercion de vehiculo y de evento outbox en la misma transaccion; relay programado y envio a Kafka.
- INFERRED: topic real del evento (depende de `OutboxEvent.pending`, que no se leyo; el publisher usa `integration.events` si es vacio); status de error del VIN duplicado (no hay 409; `uq_vehicle_tenant_vin` tambien protege ante carrera). Sin 403.
- Marca: INFERRED_FROM_CONTROLLER (no hay OpenAPI).
