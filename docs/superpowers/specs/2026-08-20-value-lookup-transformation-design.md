# Design Spec: Tenant-Scoped Value Lookups / Codifiers in Data Transformation Engines

## 1. Executive Summary & Goals

The goal of this feature is to provide the System Integrator platform with **Value Lookup / Codifier (Value Mapping)** capabilities.
This enables declarative translation of source codes (from external systems such as SIGO, SAP, etc.) into target canonical codes expected by CL2 Core APIs, with automatic fallback to a configured default value when a lookup entry is not found.

Key requirements:
1. **Tenant Isolation**: Lookups are strictly segregated by `tenant_id` and `external_source`.
2. **Persistence & APIs**: Lookups are stored in MySQL (`integration_value_lookup`) and managed via REST APIs (`/api/v1/lookups`).
3. **Transformation Engine Integration**:
   - Supported in `FIELD_MAPPING` rules via `lookup: { catalogCode, defaultValue }`.
   - Supported in `JSLT` transformation scripts via custom extension function `lookup(catalogCode, sourceValue, defaultValue)`.
4. **High Performance**: Thread-safe caching of lookup entries to ensure near-zero latency during bulk ingestions.

---

## 2. Data Model & Storage

### 2.1 Flyway Migration (`V8__create_integration_value_lookup.sql`)
```sql
CREATE TABLE integration_value_lookup (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    external_source VARCHAR(100) NOT NULL,
    catalog_code VARCHAR(100) NOT NULL,
    source_value VARCHAR(255) NOT NULL,
    target_value VARCHAR(255) NOT NULL,
    description VARCHAR(255) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_lookup_source (tenant_id, external_source, catalog_code, source_value),
    KEY idx_lookup_query (tenant_id, external_source, catalog_code, active)
);
```

### 2.2 Domain Entity `ValueLookup`
- `UUID id`
- `UUID tenantId`
- `String externalSource` (e.g. "sigo", "sap")
- `String catalogCode` (e.g. "TIPO_VEHICULO", "ESTADO_UNIDAD")
- `String sourceValue` (e.g. "1", "EST_ACT")
- `String targetValue` (e.g. "AUTO", "ACTIVE")
- `String description`
- `boolean active`
- `Instant createdAt`, `Instant updatedAt`

---

## 3. REST API Management (`/api/v1/lookups`)

All endpoints require `X-Tenant-ID` header (or Keycloak JWT with `tenant_id` claim):

### 3.1 Create or Update Lookup
- **`POST /api/v1/lookups`**
- Request Body:
  ```json
  {
    "externalSource": "sigo",
    "catalogCode": "TIPO_VEHICULO",
    "sourceValue": "1",
    "targetValue": "AUTO",
    "description": "Mapeo de tipos de vehículo"
  }
  ```

### 3.2 Batch Upsert
- **`POST /api/v1/lookups/batch`**
- Request Body:
  ```json
  [
    { "externalSource": "sigo", "catalogCode": "TIPO_VEHICULO", "sourceValue": "1", "targetValue": "AUTO" },
    { "externalSource": "sigo", "catalogCode": "TIPO_VEHICULO", "sourceValue": "2", "targetValue": "CAMION" }
  ]
  ```

### 3.3 Query Catalog
- **`GET /api/v1/lookups?externalSource=sigo&catalogCode=TIPO_VEHICULO`**
- Returns list of active mappings.

### 3.4 Delete Lookup
- **`DELETE /api/v1/lookups/{id}`**

---

## 4. Transformation Engines Integration

### 4.1 `ValueLookupService`
- In-memory thread-safe cache (`ConcurrentHashMap`) indexed by `tenantId:externalSource:catalogCode:sourceValue`.
- Method:
  ```java
  public String lookup(UUID tenantId, String externalSource, String catalogCode, String sourceValue, String defaultValue)
  ```

### 4.2 Field Mapping Engine (`FIELD_MAPPING`)
- `FieldMappingRule` updated with optional `LookupRule lookup`:
  ```json
  {
    "target": "tipoUnidadId",
    "sourcePath": "$.tipo_vehiculo",
    "lookup": {
      "catalogCode": "TIPO_VEHICULO",
      "defaultValue": "2"
    }
  }
  ```
- Evaluated during transformation before type conversion.

### 4.3 JSLT Engine (`JSLT`)
- Custom JSLT Function `lookup(catalogCode, sourceValue, defaultValue)` registered in `JsltPayloadTransformer`:
  ```javascript
  {
    "tipoUnidadId": number(lookup("TIPO_VEHICULO", .tipo, "2")),
    "alias": .placa
  }
  ```
- Resolves value using current tenant and profile externalSource.

---

## 5. Verification & Test Plan

1. **Unit & Repository Tests**:
   - `ValueLookupRepositoryTest`: Verify persistence, multi-tenant isolation, unique constraints.
   - `ValueLookupControllerTest`: Test POST, POST /batch, GET, DELETE endpoints with TenantContext.
2. **Transformation Tests**:
   - `FieldMappingPayloadTransformerTest`: Test lookup translation with existing values and default fallbacks.
   - `JsltPayloadTransformerTest`: Test JSLT script executing `lookup(...)` function.
3. **End-to-End Test**:
   - Full flow: Extraction -> Transformation with Value Lookups -> Outbox Dispatch.
