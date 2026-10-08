---
okf_version: "0.2"
title: C4 Nivel 4 - Code
c4_level: Code
focus_id: IntegrationSyncOrchestrator
focus_name: IntegrationSyncOrchestrator
focus_kind: code
focus_highlight: dark-red-700-border
status: REQUIRES_REVIEW
human-reviewed: false
---

# C4 Code: IntegrationSyncOrchestrator

Las dependencias salen del constructor de [IntegrationSyncOrchestrator.java](../../application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java). El constructor también recibe un `ResilienceExecutor`, un `ObjectMapper` y las métricas, y hay otro parámetro opcional (el 4.º) que no identifiqué. Los omití para no sobrecargar el diagrama.

```mermaid
classDiagram
    class IntegrationSyncOrchestrator {
        +run(IntegrationProfile profile) void
        -readExtractionConfig(IntegrationProfile) ExtractionConfig
        -extractRows(profile, config, secret, watermark, protocol) List
        -extractJdbcRows(profile, config, secret, watermark) List
        -findMaxRowTimestamp(rows, column, watermark) Instant
        -readWatermarkTimestamp(row, column) Instant
    }
    class SecretResolver { <<interface>> +resolve(credentialRef, tenantId) ResolvedSecret }
    class JdbcDataSourceFactory
    class GenericJdbcAdapter
    class TransformationService { +transform(json, profile) String }
    class OutboxRepository { <<interface>> }
    class SyncStateRepository { <<interface>> +find(profileId) +upsert(SyncState) }
    class SyncStateRecorder { +recordFailure() +recordCancelled() }
    class VaultSecretResolver
    class InMemorySecretResolver
    IntegrationSyncOrchestrator --> SecretResolver
    IntegrationSyncOrchestrator --> JdbcDataSourceFactory
    IntegrationSyncOrchestrator --> GenericJdbcAdapter
    IntegrationSyncOrchestrator --> TransformationService
    IntegrationSyncOrchestrator --> OutboxRepository
    IntegrationSyncOrchestrator --> SyncStateRepository
    IntegrationSyncOrchestrator --> SyncStateRecorder
    SecretResolver <|.. VaultSecretResolver
    SecretResolver <|.. InMemorySecretResolver
    style IntegrationSyncOrchestrator stroke:#B71C1C,stroke-width:4px
    style SecretResolver stroke:#616161,stroke-width:2px
    style JdbcDataSourceFactory stroke:#616161,stroke-width:2px
    style GenericJdbcAdapter stroke:#616161,stroke-width:2px
    style TransformationService stroke:#616161,stroke-width:2px
    style OutboxRepository stroke:#616161,stroke-width:2px
    style SyncStateRepository stroke:#616161,stroke-width:2px
    style SyncStateRecorder stroke:#616161,stroke-width:2px
    style VaultSecretResolver stroke:#616161,stroke-width:2px
    style InMemorySecretResolver stroke:#616161,stroke-width:2px
```

Comportamiento de `run(profile)` (líneas 111-205):
1. Resuelve el secreto con `credentialRef` y lee el watermark previo.
2. Extrae filas según el protocolo; el caso `JDBC` usa `GenericJdbcAdapter`.
3. Transforma cada fila (o el lote) con `TransformationService` y escribe en el outbox.
4. En éxito hace `syncStateRepository.upsert(...)` con el watermark avanzado y estado `SUCCESS`.
5. En cancelación o fallo, `SyncStateRecorder` registra el estado.

La firma exacta de los métodos privados es una simplificación (`INFERRED`): no leí sus cuerpos completos.
