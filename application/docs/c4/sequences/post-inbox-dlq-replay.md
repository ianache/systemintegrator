---
okf_version: "0.2"
c4_level: Sequence
endpoint: "POST /api/v1/inbox/dlq/replay"
operation: "DeadLetterQueueController.replay"
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/inbox/dlq/replay

Reproducir mensajes en DLQ del tenant. Origen: INFERRED_FROM_CONTROLLER (no existe OpenAPI). Participante foco marcado con ★.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Cliente (UI/BFF)
    participant TF as TenantFilter
    participant C as ★ DeadLetterQueueController
    participant S as DeadLetterQueueReplayService
    participant R as SpringDataInboxRepository
    participant D as OutboundEventDispatcher
    participant X as Perfiles/Vault/HttpOutboundClient
    participant DB as MySQL
    participant EH as ApiExceptionHandler

    Client->>TF: POST /api/v1/inbox/dlq/replay + X-Tenant-ID
    alt Header invalido
        TF-->>Client: 400 ProblemDetail (TENANT_HEADER_*)
    else Header valido
        TF->>C: TenantContext.set(tenantId)
        C->>C: requireTenantId()
        C->>S: replay(tenantId) [@Transactional]
        S->>R: findByTenantIdAndStatus(tenantId, "DEAD_LETTER")
        R->>DB: SELECT inbox WHERE status = DEAD_LETTER
        DB-->>R: mensajes DLQ
        loop cada mensaje DLQ
            S->>S: batchContextResolver.recoverFromEvent(eventType, payload)
            S->>D: dispatch(eventId, tenantId, eventType, payload, null, batchContext)
            D->>X: perfiles activos, resolve secreto, transformar, HTTP send (ver post-messages-direction-id-retry)
            alt dispatch OK
                S->>S: entity.markProcessed(now)
                S->>R: save(entity)
                R->>DB: UPDATE inbox (PROCESSED)
            else Excepcion
                S->>S: entity.markDeadLetter(ex.getMessage()) y log.warn
                S->>R: save(entity)
                R->>DB: UPDATE inbox (DEAD_LETTER)
            end
        end
        S-->>C: ReplaySummary(total, success, failed)
        C-->>Client: 200 JSON
    end
    opt Error inesperado (p.ej. fallo de BD)
        S->>EH: Exception
        EH-->>Client: 500 INTERNAL_ERROR
    end
```

## Archivos fuente

- [DeadLetterQueueController](../../../src/main/java/com/cl2/integration/adapter/in/web/DeadLetterQueueController.java)
- [DeadLetterQueueReplayService](../../../src/main/java/com/cl2/integration/integration/inbox/DeadLetterQueueReplayService.java)
- [SpringDataInboxRepository](../../../src/main/java/com/cl2/integration/integration/inbox/SpringDataInboxRepository.java)
- [OutboundEventDispatcher](../../../src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java)
- [HttpOutboundClient](../../../src/main/java/com/cl2/integration/adapter/out/http/HttpOutboundClient.java)
- [SecretResolver](../../../src/main/java/com/cl2/integration/integration/security/SecretResolver.java)
- [IntegrationProfilePersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)

## Procedencia

- Contrato del endpoint: INFERRED_FROM_CONTROLLER.
- EXTRACTED: controller y service. INFERRED: detalle interno del dispatcher resumido; no hay uso de Kafka en este camino (DeadLetterQueuePublisher no se invoca desde el replay).
- Todas las rutas (excepto /actuator) exigen cabecera X-Tenant-ID (TenantFilter).
