---
okf_version: "0.2"
c4_level: Sequence
endpoint: "DELETE /api/v1/lookups/{id}"
operation: deleteValueLookup
status: REQUIRES_REVIEW
human-reviewed: false
---

# DELETE /api/v1/lookups/{id} (INFERRED_FROM_CONTROLLER)

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant F as TenantFilter
    participant K as ★ ValueLookupController
    participant S as ValueLookupService
    participant P as ValueLookupPersistenceAdapter
    participant DB as MySQL integration_value_lookup

    C->>F: DELETE /api/v1/lookups/{id} (X-Tenant-ID)
    alt header ausente o no UUID
        F-->>C: 400 TENANT_HEADER_MISSING / TENANT_HEADER_MALFORMED
    else tenant valido
        F->>F: TenantContext.set(tenantId)
        F->>K: delete(@PathVariable UUID id)
        alt id no es UUID
            K-->>C: 400 (MethodArgumentTypeMismatchException, default de Spring)
        else id valido
            K->>K: TenantContext.requireTenantId()
            K->>S: deleteById(tenantId, id)
            Note over S: @Transactional
            S->>P: deleteById(tenantId, id)
            P->>DB: DELETE WHERE tenant_id = ? AND id = ?
            Note over DB: filtra por tenant; id de otro tenant o inexistente borra 0 filas
            DB-->>P: n filas (no se evalua)
            P-->>S: void
            S->>S: invalidateTenantCache(tenantId)
            S-->>K: void
            K-->>C: 204 No Content (tambien si no existia; no hay 404)
        end
    end
    F->>F: TenantContext.clear() (finally)
```

## Archivos fuente

- [ValueLookupController](../../src/main/java/com/cl2/integration/integration/lookup/adapter/in/web/ValueLookupController.java)
- [ValueLookupService](../../src/main/java/com/cl2/integration/integration/lookup/application/ValueLookupService.java)
- [ValueLookupPersistenceAdapter](../../src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/ValueLookupPersistenceAdapter.java)
- [SpringDataValueLookupRepository](../../src/main/java/com/cl2/integration/integration/lookup/adapter/out/persistence/SpringDataValueLookupRepository.java)
- [TenantFilter](../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)

## Procedencia

- EXTRACTED: `@Modifying @Query DELETE ... WHERE tenantId AND id`; invalidacion de cache del tenant completo; `@ResponseStatus(NO_CONTENT)`.
- INFERRED: ausencia de 404 (no se comprueba el numero de filas). El aislamiento de tenant es silencioso (no 403).
- Marca: INFERRED_FROM_CONTROLLER (no hay OpenAPI).
