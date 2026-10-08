---
okf_version: "0.2"
c4_level: Sequence
endpoint: "POST /api/v1/transformations/preview"
operation: "TransformationController.preview"
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/transformations/preview

Previsualizar una transformacion. Origen: INFERRED_FROM_CONTROLLER (no existe OpenAPI). Participante foco marcado con ★.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Cliente (UI/BFF)
    participant TF as TenantFilter
    participant C as ★ TransformationController
    participant E as TransformationEngineType
    participant S as TransformationPreviewService
    participant T as PayloadTransformer (Passthrough/Field/Jslt/Mustache/Velocity)
    participant EH as ApiExceptionHandler

    Client->>TF: POST /api/v1/transformations/preview {engine, script, payload} + X-Tenant-ID
    alt Header invalido
        TF-->>Client: 400 ProblemDetail (TENANT_HEADER_*)
    else Header valido
        TF->>C: TenantContext.set(tenantId)
        alt Body ilegible
            C->>EH: HttpMessageNotReadableException
            EH-->>Client: 400 BAD_REQUEST
        else Body valido
            C->>C: requireTenantId() (solo valida, no usa el valor)
            C->>E: fromString(request.engine())
            E-->>C: motor (PASSTHROUGH si nulo, vacio o desconocido)
            C->>S: preview(engine, script, payload)
            alt No hay transformer registrado para el motor
                S-->>C: TransformationPreviewResult.failure("No hay un motor ...")
            else Motor registrado
                S->>S: construir configJson {engine, script}
                S->>T: transform(payload, configJson)
                alt Transformacion OK
                    T-->>S: output
                    S-->>C: TransformationPreviewResult.success(output)
                else Excepcion del motor
                    T-->>S: Exception
                    S-->>C: TransformationPreviewResult.failure(mensaje)
                end
            end
            C-->>Client: 200 JSON (los fallos de transformacion se devuelven en el resultado, no como HTTP error)
        end
    end
```

## Archivos fuente

- [TransformationController](../../../src/main/java/com/cl2/integration/adapter/in/web/TransformationController.java)
- [TransformationPreviewService](../../../src/main/java/com/cl2/integration/integration/transformation/TransformationPreviewService.java)
- [TransformationEngineType](../../../src/main/java/com/cl2/integration/integration/transformation/TransformationEngineType.java)
- [PayloadTransformer](../../../src/main/java/com/cl2/integration/integration/transformation/PayloadTransformer.java)
- [TransformationPreviewRequest](../../../src/main/java/com/cl2/integration/adapter/in/web/dto/TransformationPreviewRequest.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)

## Procedencia

- Contrato del endpoint: INFERRED_FROM_CONTROLLER.
- EXTRACTED: controller y service (sin persistencia ni I/O externo). INFERRED: comportamiento interno de cada motor no revisado.
- Todas las rutas (excepto /actuator) exigen cabecera X-Tenant-ID (TenantFilter).
