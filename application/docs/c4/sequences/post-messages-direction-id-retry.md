---
okf_version: "0.2"
c4_level: Sequence
endpoint: "POST /api/v1/messages/{direction}/{id}/retry"
operation: "MessageMonitorController.retry"
status: REQUIRES_REVIEW
human-reviewed: false
---

# POST /api/v1/messages/{direction}/{id}/retry

Reintentar mensaje. Origen: INFERRED_FROM_CONTROLLER (no existe OpenAPI). Participante foco marcado con ★.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Cliente (UI/BFF)
    participant TF as TenantFilter
    participant C as ★ MessageMonitorController
    participant S as MessageMonitorService
    participant IR as SpringDataInboxRepository
    participant OR as SpringDataOutboxRepository
    participant D as OutboundEventDispatcher
    participant P as IntegrationProfileRepository
    participant V as SecretResolver (Vault)
    participant T as TransformationService
    participant H as HttpOutboundClient
    participant X as Sistema externo REST
    participant DB as MySQL
    participant EH as ApiExceptionHandler

    Client->>TF: POST /api/v1/messages/{direction}/{id}/retry + X-Tenant-ID
    alt Header invalido
        TF-->>Client: 400 ProblemDetail (TENANT_HEADER_*)
    else Header valido
        TF->>C: TenantContext.set(tenantId)
        C->>S: retry(tenantId, direction, id) [@Transactional]
        alt direction == INBOUND
            S->>IR: findByEventIdAndTenantId(id, tenantId)
            IR->>DB: SELECT inbox
            alt No existe
                S->>EH: MessageNotFoundException
                EH-->>Client: 404 MESSAGE_NOT_FOUND
            else Existe
                S->>D: dispatch(eventId, tenantId, eventType, payload, null, batchContext)
                D->>P: findAll(tenantId, activos)
                P->>DB: SELECT integration_profile
                opt Hay perfiles OUTBOUND/BIDIRECTIONAL REST que coinciden con el dominio
                    D->>V: resolve(credentialRef, tenantId)
                    D->>T: transform(payload, profile) (omitido si batch)
                    D->>H: send(endpoint, secret, payload, tenantId) via ResilienceExecutor
                    H->>X: HTTP request
                    X-->>H: respuesta
                end
                alt dispatch OK
                    S->>S: entity.markProcessed(now)
                else Excepcion en dispatch
                    S->>S: entity.markDeadLetter(ex.getMessage())
                end
                S->>IR: save(entity)
                IR->>DB: UPDATE inbox
                S-->>C: MessageDetail
                C-->>Client: 200 JSON (estado PROCESSED o DLQ)
            end
        else direction == OUTBOUND
            S->>OR: findByIdAndTenantId(id, tenantId)
            OR->>DB: SELECT outbox
            alt No existe
                S->>EH: MessageNotFoundException
                EH-->>Client: 404 MESSAGE_NOT_FOUND
            else Existe
                S->>S: entity.retryNow(now) (PENDING, attempts=0)
                S->>OR: save(entity)
                OR->>DB: UPDATE outbox
                S-->>C: MessageDetail
                C-->>Client: 200 JSON (el OutboxRelayScheduler lo publica despues)
            end
        else otro valor
            S->>EH: IllegalArgumentException
            EH-->>Client: 400 BAD_REQUEST
        end
    end
```

## Archivos fuente

- [MessageMonitorController](../../../src/main/java/com/cl2/integration/adapter/in/web/MessageMonitorController.java)
- [MessageMonitorService](../../../src/main/java/com/cl2/integration/integration/monitor/MessageMonitorService.java)
- [SpringDataInboxRepository](../../../src/main/java/com/cl2/integration/integration/inbox/SpringDataInboxRepository.java)
- [SpringDataOutboxRepository](../../../src/main/java/com/cl2/integration/integration/outbox/SpringDataOutboxRepository.java)
- [OutboundEventDispatcher](../../../src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java)
- [HttpOutboundClient](../../../src/main/java/com/cl2/integration/adapter/out/http/HttpOutboundClient.java)
- [SecretResolver](../../../src/main/java/com/cl2/integration/integration/security/SecretResolver.java)
- [IntegrationProfilePersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java)
- [OutboxRelayScheduler](../../../src/main/java/com/cl2/integration/integration/outbox/OutboxRelayScheduler.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)

## Procedencia

- Contrato del endpoint: INFERRED_FROM_CONTROLLER.
- EXTRACTED: service y OutboundEventDispatcher. INFERRED: el detalle HTTP/Vault/transformacion se resume en un solo bloque; la publicacion posterior de OUTBOUND por OutboxRelayScheduler se infiere del comentario del codigo (no se leyo el scheduler).
- Todas las rutas (excepto /actuator) exigen cabecera X-Tenant-ID (TenantFilter).
