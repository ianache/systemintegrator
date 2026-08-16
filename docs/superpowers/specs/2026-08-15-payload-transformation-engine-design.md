# Design Spec: Dynamic Payload Transformation Engine

- **Date:** 2026-08-15
- **Status:** Approved
- **Scope:** Multitenant Dynamic Payload Transformation (JSONPath/SpEL Field Mapping & JSLT Scripting)

---

## 1. Context & Objective

In accordance with PRD (v2.0), the Multitenant Integration Platform must connect heterogeneous external sources (e.g. SAP, SIGO, REST, SOAP) with canonical platform domain models without hardcoding transformations inside core business microservices.

Each tenant configures `mapping` and `transformation` rules in their `IntegrationProfile`. The **Dynamic Payload Transformation Engine** provides a pluggable, secure, and high-performance strategy to transform payloads between external and canonical formats.

---

## 2. Architecture & Strategy Pattern

```
                       +-------------------------------+
                       |     TransformationService     |
                       +-------------------------------+
                                       |
                   +-------------------+-------------------+
                   |                   |                   |
                   v                   v                   v
        +--------------------+ +---------------+ +--------------------+
        | FieldMappingEngine | |  JsltEngine   | | PassthroughEngine  |
        | (JSONPath + SpEL)  | | (JSLT Script) | |  (Identity No-op)  |
        +--------------------+ +---------------+ +--------------------+
```

### 2.1 Core Types & Interfaces

```java
public enum TransformationEngineType {
    FIELD_MAPPING,
    JSLT,
    PASSTHROUGH
}

public interface PayloadTransformer {
    TransformationEngineType getEngineType();
    String transform(String sourcePayload, String configurationJson);
    void validateConfiguration(String configurationJson);
}
```

---

## 3. Transformation Engines & Configuration Syntaxes

### 3.1 Field Mapping Engine (`FIELD_MAPPING`)
Utilizes Jayway/Jackson JSONPath for value extraction and Spring Expression Language (SpEL) with `SimpleEvaluationContext` for sandboxed value mutations.

**Configuration Schema:**
```json
{
  "engine": "FIELD_MAPPING",
  "fields": [
    {
      "target": "vin",
      "sourcePath": "$.Vehiculo.NumeroChasis",
      "required": true
    },
    {
      "target": "brandCode",
      "sourcePath": "$.Vehiculo.Marca",
      "transform": "#val != null ? #val.toUpperCase() : null",
      "defaultValue": "GENERIC"
    },
    {
      "target": "modelYear",
      "sourcePath": "$.Vehiculo.Anio",
      "type": "INTEGER"
    },
    {
      "target": "active",
      "sourcePath": "$.Vehiculo.Estado",
      "transform": "#val == '1' || #val == 'ACTIVO'"
    }
  ]
}
```

### 3.2 JSLT Script Engine (`JSLT`)
Utilizes Schibsted JSLT (`com.schibsted.spt.data:jslt`) for high-performance functional JSON-to-JSON structural transformations, loops, filters, and array projections.

**Configuration Schema:**
```json
{
  "engine": "JSLT",
  "script": "{\n  \"vin\": .vehicle_identification_number,\n  \"brandCode\": uppercase(.brand),\n  \"modelCode\": .model,\n  \"modelYear\": number(.year),\n  \"accessories\": [for (.items) {\"code\": .item_id, \"name\": .desc} if (.active == true)]\n}"
}
```

### 3.3 Passthrough Engine (`PASSTHROUGH`)
Returns the source payload unaltered when no mapping/transformation is required.

---

## 4. Error Handling & Security

1. **SpEL Sandboxing**: Strict use of `SimpleEvaluationContext.forReadOnlyDataBinding()` prevents arbitrary Java method execution, filesystem access, or class loading.
2. **Missing Required Fields**: Throws `MissingRequiredFieldException` when a field marked `required: true` is missing and has no `defaultValue`.
3. **Compile-Time / Config-Time Validation**:
   - `validateConfiguration` compiles JSONPath and SpEL expressions or JSLT AST upon profile creation/update.
4. **Runtime Transformation Faults**: Wraps underlying errors into `TransformationException` with detailed context (source field, target field, engine type).

---

## 5. Testing & Verification Strategy

1. **FieldMappingPayloadTransformerTest**:
   - Direct field extraction via JSONPath.
   - SpEL transformations (string manipulation, conditionals, arithmetic).
   - Type conversions (String, Integer, Long, Boolean).
   - Default value fallback on null/missing fields.
   - Exception on missing required fields.
2. **JsltPayloadTransformerTest**:
   - Structural transformations with nested objects.
   - Array iterations, filtering, and projections.
   - Syntax validation error handling.
3. **TransformationServiceIntegrationTest**:
   - Profile-driven strategy resolution.
   - Integration with canonical event dispatching.
4. **Full Reactor Verification**:
   - `mvn clean test` must pass 100% across all modules.
