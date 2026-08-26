# Análisis de Arquitectura de la Solución: Plataforma Multitenant de Integración Flexible

Este documento presenta la arquitectura integral, patrones de diseño, componentes desacoplados, modelos de datos, flujos de eventos y seguridad de la **Plataforma Multitenant de Integración Flexible** para integraciones Inbound y Outbound con bases de datos relacionales (JDBC) y APIs REST aseguradas con Keycloak y HashiCorp Vault.

---

## 1. Diagrama General de Arquitectura

El siguiente diagrama ilustra la topología completa de la solución, sus capas de seguridad en el borde, backend hexagonal multitenant, el bus de eventos segregado por dominio, la gestión de secretos en Vault y los adaptadores genéricos declarativos.

```mermaid
flowchart TD
    subgraph Clients["Clientes & Sistemas Externos"]
        WebApp["Web / Mobile App / B2B Client"]
        ExternalDB["Bases de Datos Externas (MySQL, SAP HANA, Oracle, PostgreSQL, SQL Server)"]
        ExternalAPI["APIs REST Externas / Microservicios Core (Keycloak OIDC)"]
    end

    subgraph IAM["Seguridad & Gestión de Identidad"]
        Keycloak["Keycloak OAuth2 / OIDC Server\n(Realm: microservicios / JWKS)"]
        Vault["HashiCorp Vault (KV v2)\n(secret/cl2/*, secret/sigo/*)"]
    end

    subgraph EdgeLayer["Capa de Borde / Gateway (Puerto 8081)"]
        GW["Spring Cloud Gateway"]
        JWTSec["Validación JWT (JWKS Keycloak)"]
        TenantExt["Extracción de claim 'tenant_id'"]
        HeaderInj["Inyección 'X-Tenant-ID' / Sanitización"]
    end

    subgraph CoreBackend["Core Backend Application (Puerto 8080)"]
        subgraph HexagonalArch["Arquitectura Hexagonal (Ports & Adapters)"]
            RESTApi["REST Controllers (/api/v1/...)"]
            TenantCtx["TenantContext (ThreadLocal)"]
            ProfileEngine["IntegrationProfile Engine (CRUD, Validaciones)"]
            ProfileDeact["ProfileDeactivationHandler (Cancelación Asíncrona)"]
            SyncScheduler["IntegrationSyncScheduler (CRON / ShedLock)"]
            SyncOrchestrator["IntegrationSyncOrchestrator (Extracción & Watermark)"]
            TransformationSvc["TransformationService\n(JSLT Engine + Value Lookups)"]
            OutboxRelay["OutboxRelayScheduler (Transactional Outbox)"]
            KafkaInbox["KafkaInboxListener (topicPattern regex)"]
            InboxProc["InboxProcessor (Idempotencia & Deduplicación)"]
            OutboundDisp["OutboundEventDispatcher (Anti-Loop Filter)"]
            HttpOutbound["HttpOutboundClient (DEBUG Redaction & Auth Injection)"]
        end

        subgraph GenericAdapters["Motor de Adaptadores Genéricos Declarativos (Zero-Code)"]
            GenericJdbc["GenericJdbcAdapter\n(Extracción JDBC Parametrizada)"]
            SqlValidator["SqlSecurityValidator\n(Guardrail AST JSqlParser)"]
            TokenCache["OAuth2TokenCacheManager\n(Caché Hilo-Seguro de JWT)"]
            SecretResolv["VaultSecretResolver / InMemorySecretResolver\n(OAuth2, Basic, Bearer, APIKey, Custom Headers)"]
            ValueLookupSvc["ValueLookupService (Caché & Mapeo Cruzado)"]
        end
    end

    subgraph InfraLayer["Capa de Infraestructura & Persistencia"]
        MySQL[("MySQL 8.4 DB\n- integration_profile\n- integration_outbox\n- integration_inbox\n- integration_sync_state\n- integration_value_lookup\n- shedlock")]
        Redis[("Redis 7.4 Cache\n- Rate Limiter (Token Bucket)\n- Cached Tokens / Profiles")]
        Kafka[("Apache Kafka 3.8.1 (KRaft)\n- Topics: integration.<domain>.events\n  (units, brands, models, vehicles, customers, orders)\n- DLQ: integration.events.dlq")]
    end

    %% Flujos Principales
    WebApp -->|"HTTP Request + Bearer JWT"| GW
    Keycloak -->|"JWKS Public Keys"| JWTSec
    GW --> JWTSec --> TenantExt --> HeaderInj
    HeaderInj -->|"HTTP Request + Header X-Tenant-ID"| RESTApi
    RESTApi --> TenantCtx
    TenantCtx --> ProfileEngine

    %% Inbound JDBC Flow
    SyncScheduler -->|"Trigger programado / Manual"| SyncOrchestrator
    SyncOrchestrator -->|"Resuelve Credencial DB"| SecretResolv
    SecretResolv --> Vault
    SyncOrchestrator -->|"Obtiene Watermark previo"| MySQL
    SyncOrchestrator --> GenericJdbc
    GenericJdbc --> SqlValidator -->|"SELECT parametrizado"| ExternalDB
    ExternalDB -->|"Filas Extraídas"| SyncOrchestrator
    SyncOrchestrator --> TransformationSvc
    TransformationSvc --> ValueLookupSvc
    ValueLookupSvc --> MySQL
    SyncOrchestrator -->|"Inserta OutboxEvent PENDING"| MySQL

    %% Outbox Relay Flow
    OutboxRelay -->|"SELECT FOR UPDATE SKIP LOCKED"| MySQL
    OutboxRelay -->|"Publica con Headers de Procedencia"| Kafka

    %% Outbound Dispatch Flow
    Kafka -->|"Consume con Regex integration.*.events"| KafkaInbox
    KafkaInbox --> InboxProc
    InboxProc -->|"Deduplica e inserta Inbox"| MySQL
    InboxProc --> OutboundDisp
    OutboundDisp -->|"Anti-Loop & Match Domain"| ProfileEngine
    OutboundDisp -->|"Resuelve OAuth2 / Headers"| SecretResolv
    OutboundDisp --> TransformationSvc
    OutboundDisp --> HttpOutbound
    HttpOutbound -->|"Obtiene / Reutiliza JWT"| TokenCache
    TokenCache -->|"POST grant_type=client_credentials"| Keycloak
    HttpOutbound -->|"HTTP POST + Bearer JWT + Headers"| ExternalAPI

    %% Deactivation Flow
    RESTApi -->|"POST /deactivate"| ProfileEngine
    ProfileEngine -->|"Publica IntegrationProfileDeactivated"| ProfileDeact
    ProfileDeact -->|"Cancela Future en ejecución"| SyncOrchestrator
    ProfileDeact -->|"UPDATE status='CANCELLED'"| MySQL
```

---

## 2. Componentes de Dominio y Capas Hexagonales

### 2.1. Gestión de Perfiles (`IntegrationProfile`)
El agregado `IntegrationProfile` es el corazón declarativo del sistema:
- **`businessDomain`**: Dominio de negocio (`units`, `brands`, `models`, `vehicles`, `customers`, `orders`, `billing`, `catalog`).
- **`syncDirection`**: Sentido de integración:
  - `INBOUND`: Ingesta desde orígenes externos hacia Kafka.
  - `OUTBOUND`: Despacho desde Kafka hacia APIs o bases externas.
  - `BIDIRECTIONAL`: Operación en ambos sentidos.
- **`sourceOfTruth`**: Origen de la verdad (`SOURCE`, `PLATFORM`, `BIDIRECTIONAL`).
- **`protocol`**: Protocolo de transporte (`JDBC`, `REST`, `KAFKA`, `SOAP`).
- **`configuration`**:
  - `endpoint`: URL REST o JDBC Connection String.
  - `credentialRef`: Referencia al secreto en Vault (`vault:secret/data/...` o `secret/...`).
  - `extractionConfig`: Configuración de query SQL y columna watermark para JDBC, o endpoints, JSONPaths y `keyProperty` para REST Inbound (sin paginación en este slice).
  - `transformation`: Script declarativo `JSLT` o mapeo de campos `FIELD_MAPPING`.
  - `syncPolicy`: Expresión CRON de ejecución periódica (ej. `*/30 * * * * *`).
  - `retryPolicy`: Configuración de reintentos (`maxAttempts`, `backoffMs`).
  - `rateLimitPolicy`: Límite de peticiones por segundo (`requestsPerSecond`).

---

### 2.2. Ingesta Inbound y Sincronización JDBC (`IntegrationSyncOrchestrator`)
- **Extracción Incremental basada en Watermark**: Utiliza `lastWatermark` almacenado en `integration_sync_state` para consultar únicamente registros nuevos o modificados (`WHERE updated_at > :lastSyncWithBuffer`).
- **Validación AST de Seguridad SQL (`SqlSecurityValidator`)**:
  - Analiza el árbol sintáctico con **JSqlParser 4.9**.
  - Exige consultas exclusivas de tipo `SELECT`.
  - Prohíbe inyecciones, DML (`INSERT`, `UPDATE`, `DELETE`), DDL (`DROP`, `ALTER`, `TRUNCATE`), multi-statements (`;`) y acceso a esquemas de catálogo del motor (`information_schema`, `mysql`, `performance_schema`, `sys`).
- **Transaccionalidad Outbox**: La persistencia de los eventos extraídos en `integration_outbox` se realiza dentro de la misma transacción relacional, garantizando consistencia atómica.

---

### 2.3. Transactional Outbox Relay (`OutboxRelayScheduler`)
- **Polling Concurrente Seguro**: Recupera lotes de eventos con `status = 'PENDING'` mediante `SELECT ... FOR UPDATE SKIP LOCKED`.
- **Encabezados de Procedencia Estándar**: Cada mensaje publicado en Kafka incluye cabeceras para trazabilidad y anti-bucle:
  - `X-Tenant-ID`: Identificador único del tenant emisor.
  - `X-Aggregate-ID`: Clave de negocio de la entidad.
  - `X-Event-Type`: Tipo de evento (ej. `units.upserted`, `customers.created`).
  - `X-Business-Domain`: Dominio de negocio (ej. `units`, `customers`).
  - `X-External-Source`: Fuente externa que originó el dato (ej. `sigo-erp`, `sap-hana`).

---

### 2.4. Suscripción Dinámica de Inbox e Idempotencia (`KafkaInboxListener`)
- **Regex Topic Pattern**: Escucha dinámicamente cualquier tópico bajo el patrón `integration\..*\.events`.
- **Deduplicación en `integration_inbox`**: Verifica el `eventId` para garantizar procesamiento *exactly-once* a nivel de aplicación.
- **Dead Letter Queue (`DeadLetterQueuePublisher`)**: Desvía eventos fallidos a `integration.events.dlq` con detalle del stacktrace tras agotar los reintentos.

---

### 2.5. Despacho Outbound REST y Anti-Loop (`OutboundEventDispatcher`)
- **Filtro Anti-Loop**: Compara el `X-External-Source` recibido con el `externalSource` del perfil destino. Si coinciden, el evento se descarta para prevenir rebotes y bucles infinitos.
- **Transformación de Payload Declarativa**: Aplica transformaciones `JSLT` con soporte para funciones personalizadas de catálogo como `lookup("CATALOG_CODE", .source_field, "DEFAULT_VALUE")`.
- **Inyección de Tokens y Cabeceras en `HttpOutboundClient`**:
  - Resuelve secretos OAuth2 desde Vault.
  - Solicita o reutiliza tokens JWT desde `OAuth2TokenCacheManager` (con caché hilo-segura basada en el TTL del JWT).
  - Inyecta `Authorization: Bearer <TOKEN>` y cabeceras fijas (`x-audit`, `X-Distribuidor-Id`, `X-API-Key`, etc.).
  - Aplica enmascaramiento en logs en modo `DEBUG` preservando los primeros 3 caracteres y protegiendo el resto con `[REDACTED]`.

---

### 2.6. Ciclo de Vida y Tratamiento en Desactivación de Perfiles (`ProfileDeactivationHandler`)
Al invocar `POST /api/v1/integration-profiles/{profileId}/deactivate`:
1. El perfil se marca como `active = false`.
2. Se publica el evento de dominio `IntegrationProfileDeactivated`.
3. `ProfileDeactivationHandler` ejecuta:
   - **Interrupción de Hilos en Curso**: Invoca `cancelRunningExecution(profileId)`, enviando una señal cooperativa `Thread.interrupt()` al hilo de sincronización.
   - **Cancelación Masiva de Outbox**: Actualiza en lote todos los eventos `PENDING` para ese tenant y tópico a estado `CANCELLED` (`UPDATE integration_outbox SET status = 'CANCELLED' ...`).
   - **Protección del Watermark**: Se registra el estado de sincronización como `CANCELLED` en `integration_sync_state`, manteniendo inalterado el último watermark exitoso anterior.

---

## 3. Modelo de Datos y Esquema Relacional

```mermaid
erDiagram
    INTEGRATION_PROFILE ||--o{ INTEGRATION_SYNC_STATE : "monitorea"
    INTEGRATION_PROFILE {
        binary(16) id PK
        binary(16) tenant_id
        varchar(100) business_domain
        varchar(100) external_source
        varchar(20) sync_direction
        varchar(20) source_of_truth
        varchar(20) protocol
        varchar(100) connector
        varchar(100) adapter
        varchar(500) endpoint
        varchar(255) credential_ref
        json mapping_json
        json transformation_json
        json sync_policy_json
        json retry_policy_json
        json rate_limit_policy_json
        json extraction_config_json
        boolean active
        bigint version
        timestamp created_at
        timestamp updated_at
    }

    INTEGRATION_OUTBOX {
        binary(16) id PK
        binary(16) tenant_id
        binary(16) aggregate_id
        varchar(100) aggregate_type
        varchar(150) event_type
        varchar(150) topic
        json payload
        varchar(20) status
        int attempts
        varchar(1000) last_error
        timestamp available_at
        timestamp created_at
        timestamp updated_at
    }

    INTEGRATION_INBOX {
        binary(16) event_id PK
        binary(16) tenant_id
        varchar(150) event_type
        json payload
        varchar(20) status
        int attempts
        varchar(1000) last_error
        timestamp received_at
        timestamp processed_at
    }

    INTEGRATION_SYNC_STATE {
        binary(16) profile_id PK
        timestamp last_watermark
        timestamp last_run_started_at
        varchar(20) last_run_status
        varchar(1000) last_error
    }

    INTEGRATION_VALUE_LOOKUP {
        binary(16) id PK
        binary(16) tenant_id
        varchar(100) external_source
        varchar(100) catalog_code
        varchar(150) source_value
        varchar(150) target_value
        timestamp created_at
        timestamp updated_at
    }

    SHEDLOCK {
        varchar(64) name PK
        timestamp lock_until
        timestamp locked_at
        varchar(255) locked_by
    }
```

---

## 4. Matriz de Stack Tecnológico y Responsabilidades

| Componente | Versión / Librería | Propósito Arquitectónico |
|---|---|---|
| **Runtime** | Java 21 LTS | Soporte de Virtual Threads, Records y Pattern Matching. |
| **Framework Base** | Spring Boot 3.4.5 | Inyección de dependencias, gestión transaccional y componentes modulares. |
| **Borde / Gateway** | Spring Cloud Gateway | Validación de JWT de Keycloak, extracción de Tenant ID y enrutamiento seguro. |
| **Gestión de Identidad** | Keycloak 26.x | Servidor de autorización OAuth2 / OIDC con soporte para Client Credentials. |
| **Almacén de Secretos** | HashiCorp Vault 1.18 (KV v2) | Almacenamiento seguro de credenciales DB y secretos de cliente OAuth2. |
| **Base de Datos Principal** | MySQL 8.4 | Persistencia relacional ACID de perfiles, estados y patrones Outbox/Inbox. |
| **Control de Versiones BD** | Flyway | Migraciones reproducibles y versionadas de bases de datos. |
| **Event Broker** | Apache Kafka 3.8.1 (KRaft) | Bus distribuido de eventos desacoplados por dominio (`integration.<domain>.events`). |
| **Caché y Concurrencia** | Redis 7.4 | Almacenamiento en caché y algoritmos Token Bucket para Rate Limiting. |
| **Motor de Transformación** | JSLT (Schibsted) + Jackson | Transformación declarativa JSON a JSON con funciones nativas de Value Lookup. |
| **Seguridad SQL** | JSqlParser 4.9 | Análisis AST para restringir estrictamente queries Inbound a solo `SELECT`. |
| **Resiliencia & Coordinación** | Resilience4j + ShedLock | Circuit Breaker por conector/tenant y bloqueos distribuidos de ejecución. |
