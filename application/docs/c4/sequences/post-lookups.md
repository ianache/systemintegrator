---
okf_version: "0.2"
c4_level: Sequence
endpoint: "POST /api/v1/lookups"
operation: createValueLookup
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/lookups (INFERRED_FROM_CONTROLLER)

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant F as TenantFilter
    participant K as ★ ValueLookupController
    participant S as ValueLookupService
    participant D as ValueLookup (domain)
    participant P as ValueLookupPersistenceAdapter
    participant DB as MySQL integration_value_lookup

    C->>F: POST /api/v1/lookups (X-Tenant-ID, body)
    alt header ausente o no UUID
        F-->>C: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        F->>F: TenantContext.set(tenantId)
        F->>K: create(@Valid CreateValueLookupRequest)
        alt Bean Validation falla (externalSource/catalogCode/sourceValue/targetValue en blanco)
            K-->>C: 400 (MethodArgumentNotValidException)
        else body valido
            K->>K: TenantContext.requireTenantId()
            K->>S: save(tenantId, request)
            Note over S: @Transactional
            S->>D: ValueLookup.create(id|random, tenantId, ..., active default true)
            D-->>S: ValueLookup
            S->>P: save(lookup)
            P->>DB: INSERT/MERGE (JPA save)
            alt viola uq_lookup_tenant_source_catalog_value
                DB-->>P: DataIntegrityViolationException
                P-->>C: error no mapeado (ver AMBIGUOUS: 500, no 409)
            else ok
                DB-->>P: fila
                P-->>S: ValueLookup
                S->>S: invalidateCache(tenant, source, catalog, sourceValue)
                S-->>K: ValueLookup
                K-->>C: 201 ValueLookupResponse
            end
        end
    end
    F->>F: TenantContext.clear() (finally)
```

## Archivos fuente

- [ValueLookupController](../../src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/ValueLookupController.java)
- [CreateValueLookupRequest](../../src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/dto/CreateValueLookupRequest.java)
- [ValueLookupService](../../src/main/java/com/cl2/integration/integration/lookup/application/ValueLookupService.java)
- [ValueLookup](../../src/main/java/com/cl2/integration/integration/lookup/domain/ValueLookup.java)
- [ValueLookupPersistenceAdapter](../../src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/ValueLookupPersistenceAdapter.java)
- [TenantFilter](../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [V8__create_integration_value_lookup.sql](../../src/main/resources/db/migration/V8__create_integration_value_lookup.sql)

## Procedencia

- EXTRACTED: flujo controller -> service -> domain -> adapter -> MySQL, validaciones, cache, 201 y 400 del filtro de tenant.
- INFERRED: status 400 por validacion (default de Spring) y 500 por violacion de unicidad. `ApiExceptionHandler` solo cubre el paquete `adapter.in.web` raiz, no este controller. No hay Kafka/outbox en este endpoint. No existe 403 de tenant: la falta de tenant produce 400.
- Marca: INFERRED_FROM_CONTROLLER (no hay OpenAPI).
