---
okf_version: "0.2"
title: C4 Nivel 3 - Component
c4_level: Component
focus_id: IntegrationSyncOrchestrator
focus_name: IntegrationSyncOrchestrator
focus_kind: component
focus_highlight: dark-red-700-border
status: REQUIRES_REVIEW
human-reviewed: false
---

# C4 Component: integration-app

Arquitectura descubierta: hexagonal. Hay `domain/port`, `adapter/in/web`, `adapter/out/*`, `application` e `infrastructure`. Encima hay un paquete `integration/*` con los motores de sincronización, mensajería y transformación.

Este diagrama muestra el subconjunto de componentes que participan en las secuencias S2 y S3. No lista los de `flow`, `lookup`, `vehicle`, `monitor` ni `batch`.

```mermaid
flowchart LR
    subgraph web["adapter.in.web / infrastructure.tenant"]
        tf["TenantFilter + TenantContext<br/>exige X-Tenant-ID (UUID)"]
        ctl["IntegrationProfileController<br/>/api/v1/integration-profiles"]
    end
    subgraph appl["application"]
        ps["IntegrationProfileService"]
    end
    subgraph syncpkg["integration.sync"]
        iss["IntegrationSyncService"]
        sch["IntegrationSyncScheduler"]
        orch["IntegrationSyncOrchestrator<br/>extracción, transformación, watermark"]
    end
    subgraph ext["adapters y servicios de soporte"]
        sec["SecretResolver<br/>(VaultSecretResolver)"]
        jdbc["GenericJdbcAdapter"]
        tr["TransformationService"]
        ob["OutboxRepository / OutboxRelayScheduler / KafkaOutboxPublisher"]
        kin["KafkaInboxListener + InboxProcessor"]
        od["OutboundEventDispatcher"]
        ho["HttpOutboundClient"]
    end
    repo["IntegrationProfileRepository (port)<br/>IntegrationProfilePersistenceAdapter"]

    tf --> ctl --> ps --> repo
    ctl --> iss
    sch --> iss
    iss --> orch
    orch --> sec
    orch --> jdbc
    orch --> tr
    orch --> ob
    ob -. "Kafka" .-> kin
    kin --> od
    od --> repo
    od --> sec
    od --> tr
    od --> ho

    classDef focus stroke:#B71C1C,stroke-width:4px,fill:#ffffff,color:#212121;
    classDef other stroke:#616161,stroke-width:2px,fill:#ffffff,color:#212121;
    class orch focus;
    class tf,ctl,ps,iss,sch,sec,jdbc,tr,ob,kin,od,ho,repo other;
```

| Componente | Responsabilidad | Código | Procedencia |
|---|---|---|---|
| TenantFilter / TenantContext | Exige `X-Tenant-ID` UUID; lo guarda en un ThreadLocal | [TenantFilter.java](../../application/src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java) | EXTRACTED |
| IntegrationProfileController | CRUD y acciones sobre perfiles | [IntegrationProfileController.java](../../application/src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java) | EXTRACTED |
| IntegrationProfileService | Casos de uso transaccionales y eventos de perfil | [IntegrationProfileService.java](../../application/src/main/java/com/cl2/integration/application/IntegrationProfileService.java) | EXTRACTED |
| IntegrationSyncService, IntegrationSyncScheduler | Disparo manual y programado de la sync | [sync](../../application/src/main/java/com/cl2/integration/integration/sync) | INFERRED (relación con el Orchestrator) |
| IntegrationSyncOrchestrator | Extrae, transforma, escribe outbox y avanza el watermark | [IntegrationSyncOrchestrator.java](../../application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java) | EXTRACTED |
| Outbox (relay, publisher) | Publica eventos en Kafka | [outbox](../../application/src/main/java/com/cl2/integration/integration/outbox) | EXTRACTED |
| KafkaInboxListener, InboxProcessor | Consume eventos y deduplica | [inbox](../../application/src/main/java/com/cl2/integration/integration/inbox) | EXTRACTED |
| OutboundEventDispatcher, HttpOutboundClient | Resuelve perfiles outbound y entrega por HTTP | [outbound](../../application/src/main/java/com/cl2/integration/integration/outbound) | EXTRACTED |
