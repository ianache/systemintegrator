---
okf_version: "0.2"
c4_level: Sequence
endpoint: "POST /api/v1/lookups/batch"
operation: createValueLookupBatch
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/lookups/batch (INFERRED_FROM_CONTROLLER)

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant F as TenantFilter
    participant K as ★ ValueLookupController
    participant S as ValueLookupService
    participant P as ValueLookupPersistenceAdapter
    participant DB as MySQL integration_value_lookup

    C->>F: POST /api/v1/lookups/batch (X-Tenant-ID, List<CreateValueLookupRequest>)
    alt header ausente o no UUID
        F-->>C: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        F->>F: TenantContext.set(tenantId)
        F->>K: createBatch(@Valid List<@Valid ...>)
        alt algun elemento invalido o JSON ilegible
            K-->>C: 400 (validacion / HttpMessageNotReadable)
        else lista valida
            K->>K: TenantContext.requireTenantId()
            K->>S: saveBatch(tenantId, requests)
            Note over S: @Transactional (una sola transaccion para toda la lista)
            loop por cada request
                S->>S: save(tenantId, request) -> ValueLookup.create(...)
                S->>P: save(lookup)
                P->>DB: INSERT/MERGE
                S->>S: invalidateCache(clave)
            end
            alt cualquier fila viola uq_lookup_tenant_source_catalog_value
                DB-->>S: DataIntegrityViolationException
                S-->>C: error no mapeado, rollback total (ver AMBIGUOUS: 500, no 409)
            else todas ok
                S-->>K: List<ValueLookup>
                K-->>C: 201 List<ValueLookupResponse>
            end
        end
    end
    F->>F: TenantContext.clear() (finally)
```

## Archivos fuente

- [ValueLookupController](../../src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/ValueLookupController.java)
- [CreateValueLookupRequest](../../src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/dto/CreateValueLookupRequest.java)
- [ValueLookupService](../../src/main/java/com/cl2/integration/integration/lookup/application/ValueLookupService.java)
- [ValueLookupPersistenceAdapter](../../src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/ValueLookupPersistenceAdapter.java)
- [TenantFilter](../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [V8__create_integration_value_lookup.sql](../../src/main/resources/db/migration/V8__create_integration_value_lookup.sql)

## Procedencia

- EXTRACTED: bucle de `save` dentro de `saveBatch` transaccional; invalidacion de cache por clave.
- INFERRED: rollback total ante fallo (por `@Transactional` de `saveBatch`); status 400/500 de errores no mapeados. Sin Kafka/outbox. Sin 403.
- Marca: INFERRED_FROM_CONTROLLER (no hay OpenAPI).
