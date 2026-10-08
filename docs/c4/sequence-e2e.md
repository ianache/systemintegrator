---
okf_version: "0.2"
title: Secuencias E2E del producto
c4_level: Sequence
focus_id: per-journey
focus_kind: participant
focus_highlight: dark-red-700-border
status: REQUIRES_REVIEW
human-reviewed: false
---

# Secuencias E2E

El foco de cada secuencia se marca con `★` en el participante. Mermaid no permite borde por participante, así que `★` sustituye al borde `dark red 700` en esta vista.

Procedencia: todos los mensajes salen de código leído (`EXTRACTED`), salvo los marcados `INFERRED`.

## S1. Login OIDC (foco: backoffice-bff)

Fuentes: [auth.controller.ts](../../backoffice/apps/bff/src/auth/auth.controller.ts), [auth.service.ts](../../backoffice/apps/bff/src/auth/auth.service.ts), [auth.guard.ts](../../backoffice/apps/shell/src/app/auth.guard.ts).

```mermaid
sequenceDiagram
    autonumber
    actor U as Administrador
    participant SH as backoffice-shell (nginx + authGuard)
    participant BFF as ★ backoffice-bff (AuthController/AuthService)
    participant R as integration-redis
    participant KC as Keycloak (realm Apps)
    U->>SH: GET / (ruta integration)
    SH->>BFF: GET /auth/session
    BFF-->>SH: authenticated=false
    SH->>BFF: GET /auth/login
    BFF->>R: guarda session.oidc {codeVerifier, state}
    BFF-->>U: 302 a Keycloak (code_challenge S256, scope openid profile)
    U->>KC: login
    KC-->>U: 302 /auth/callback?code&state
    U->>BFF: GET /auth/callback
    BFF->>KC: authorizationCodeGrant (code + codeVerifier)
    alt invalid_grant (código reutilizado)
        KC-->>BFF: 400 Code not valid
        BFF-->>U: 500
    else token válido
        KC-->>BFF: access_token, id_token, refresh_token
        alt falta tenant_id/exp en claims
            BFF-->>U: 500 missing required session claims
        else claims completos
            BFF->>R: session.tokens {access, id, refresh, tenantId, expiresAt}
            BFF-->>U: 302 /
        end
    end
```

## S2. Listar perfiles de integración (foco: IntegrationProfileController)

Atraviesa las capas completas: Shell/MFE → BFF → Gateway → API → Application → Port/Adapter → MySQL.

Fuentes: [gateway-proxy.controller.ts](../../backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts), [TenantClaimGatewayFilter.java](../../gateway/src/main/java/com/cl2/integration/gateway/security/TenantClaimGatewayFilter.java), [TenantFilter.java](../../application/src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java), [IntegrationProfileController.java](../../application/src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java), [IntegrationProfileService.java](../../application/src/main/java/com/cl2/integration/application/IntegrationProfileService.java).

```mermaid
sequenceDiagram
    autonumber
    actor U as Administrador
    participant MFE as integration-mfe (IntegrationProfileService)
    participant SH as backoffice-shell (nginx /bff/)
    participant BFF as backoffice-bff (SessionAccessTokenGuard + GatewayProxyService)
    participant GW as integration-middleware (JWT + TenantClaimGatewayFilter)
    participant TF as integration-app TenantFilter
    participant C as ★ IntegrationProfileController
    participant S as IntegrationProfileService
    participant P as IntegrationProfileRepository (port, PersistenceAdapter)
    participant DB as MySQL
    U->>MFE: abre /integration
    MFE->>SH: GET /bff/api/v1/integration-profiles?activeOnly=false
    SH->>BFF: proxy_pass
    alt sin sesión
        BFF-->>MFE: 401 Authentication required
    else sesión válida
        BFF->>BFF: refresca access token si corresponde (refresh_token)
        BFF->>GW: GET /api/v1/integration-profiles + Bearer JWT
        GW->>GW: valida JWT (issuer microservicios o Apps)
        alt tenant_id ausente o no UUID
            GW-->>BFF: 403
            BFF-->>MFE: 403 Gateway denied access
        else tenant_id UUID
            GW->>TF: GET + X-Tenant-ID
            TF->>C: TenantContext.set(tenantId)
            C->>S: list(tenantId, activeOnly)
            S->>P: findAll(tenantId, activeOnly)
            P->>DB: SELECT integration_profile
            DB-->>P: filas
            P-->>S: IntegrationProfile[]
            S-->>C: IntegrationProfileView[]
            C-->>MFE: 200 IntegrationProfileResponse[] (vía GW y BFF)
        end
    end
```

## S3. Sync inbound JDBC → outbox → Kafka → entrega outbound (foco: IntegrationSyncOrchestrator)

Fuentes: [IntegrationSyncOrchestrator.java](../../application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java), [OutboxRelayScheduler.java](../../application/src/main/java/com/cl2/integration/integration/outbox/OutboxRelayScheduler.java), [KafkaInboxListener.java](../../application/src/main/java/com/cl2/integration/integration/inbox/KafkaInboxListener.java), [OutboundEventDispatcher.java](../../application/src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java).

`INFERRED`: la llamada `IntegrationSyncService → IntegrationSyncOrchestrator.run` (no leí el cuerpo de `IntegrationSyncService`). Tampoco verifiqué qué clase invoca el cron: `IntegrationSyncScheduler` está en el paquete, pero no leí su código.

```mermaid
sequenceDiagram
    autonumber
    participant T as IntegrationSyncScheduler / POST .../sync
    participant SV as IntegrationSyncService
    participant O as ★ IntegrationSyncOrchestrator
    participant V as SecretResolver (Vault)
    participant SS as SyncStateRepository
    participant J as GenericJdbcAdapter
    participant X as BD externa
    participant TR as TransformationService
    participant OB as OutboxRepository
    participant RL as OutboxRelayScheduler / KafkaOutboxPublisher
    participant K as Kafka integration.dominio.events
    participant L as KafkaInboxListener + InboxProcessor
    participant D as OutboundEventDispatcher
    participant H as HttpOutboundClient
    participant A as API REST externa
    T->>SV: triggerSync(tenantId, profileId) / ciclo cron
    SV->>O: run(profile)
    O->>V: resolve(credentialRef, tenantId)
    O->>SS: find(profileId) watermark previo
    O->>J: extractRows(...)
    J->>X: SELECT parametrizado
    X-->>O: filas
    loop por fila/lote
        O->>TR: transform(json, profile)
        O->>OB: guarda evento (outbox)
    end
    alt éxito
        O->>SS: upsert(watermark avanzado, SUCCESS)
    else fallo o cancelación
        O->>SS: recordFailure / recordCancelled
    end
    RL->>OB: pollAndRelay (fixedDelay)
    RL->>K: publish (confirmación síncrona)
    K->>L: onMessage(record)
    L->>D: dispatch(eventId, tenantId, eventType, payload)
    D->>D: busca perfiles outbound activos (anti-loop por origen)
    alt sin perfiles coincidentes
        D-->>L: sin entrega (log INFO)
    else perfil coincide
        D->>V: resolve(credentialRef)
        D->>TR: transform
        D->>H: enviar
        H->>A: HTTP
        A-->>H: 2xx / error
    end
```
