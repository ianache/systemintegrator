---
okf_version: "0.2"
c4_level: Sequence
endpoint: "GET /api/v1/lookups"
operation: listValueLookups
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/lookups (INFERRED_FROM_CONTROLLER)

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant F as TenantFilter
    participant K as ★ ValueLookupController
    participant S as ValueLookupService
    participant P as ValueLookupPersistenceAdapter
    participant DB as MySQL integration_value_lookup

    C->>F: GET /api/v1/lookups?externalSource=&catalogCode= (X-Tenant-ID)
    alt header ausente o no UUID
        F-->>C: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        F->>F: TenantContext.set(tenantId)
        F->>K: list(externalSource, catalogCode)
        alt falta un @RequestParam obligatorio
            K-->>C: 400 (MissingServletRequestParameterException, default de Spring)
        else params presentes
            K->>K: TenantContext.requireTenantId()
            K->>S: findAll(tenantId, externalSource, catalogCode)
            Note over S: sin cache en esta ruta
            S->>P: findAll(tenantId, externalSource, catalogCode)
            Note over P: @Transactional(readOnly)
            P->>DB: SELECT ... WHERE tenant_id, external_source, catalog_code
            DB-->>P: filas (puede ser vacio)
            P-->>S: List<ValueLookup>
            S-->>K: List<ValueLookup>
            K-->>C: 200 List<ValueLookupResponse> ([] si no hay filas; sin 404)
        end
    end
    F->>F: TenantContext.clear() (finally)
```

## Archivos fuente

- [ValueLookupController](../../src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/ValueLookupController.java)
- [ValueLookupResponse](../../src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/dto/ValueLookupResponse.java)
- [ValueLookupService](../../src/main/java/com/cl2/integration/integration/lookup/application/ValueLookupService.java)
- [ValueLookupPersistenceAdapter](../../src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/ValueLookupPersistenceAdapter.java)
- [SpringDataValueLookupRepository](../../src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/SpringDataValueLookupRepository.java)
- [TenantFilter](../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)

## Procedencia

- EXTRACTED: ruta de lectura sin cache, filtro por tenant, lista vacia como resultado.
- INFERRED: 400 por parametro faltante (default de Spring). Sin Kafka/outbox. Sin 403/404.
- Marca: INFERRED_FROM_CONTROLLER (no hay OpenAPI).
