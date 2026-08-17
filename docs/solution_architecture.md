# Análisis de Arquitectura de la Solución: Plataforma Multitenant de Integración Flexible

Este documento presenta el análisis técnico detallado y la visualización arquitectónica de la **Plataforma Multitenant de Integración Flexible**, el **MVP SIGO Vehicle** y el **Motor Declarativo de Adaptadores Genéricos (Zero-Code Adapters)**.

---

## 1. Diagrama General de Arquitectura

El siguiente diagrama representa la arquitectura enterprise de la solución, sus capas, componentes clave, el motor declarativo de adaptadores y el flujo de datos desde la autenticación del cliente hasta los adaptadores y sistemas externos.

![Diagrama de Arquitectura de la Solución](architecture.png)

---

## 2. Diagrama de Flujo y Componentes (Mermaid)

```mermaid
flowchart TD
    subgraph Clients["Clientes & Sistemas Externos"]
        WebApp["Web / Mobile App / B2B"]
    end

    subgraph IAM["Identity & Access Management"]
        Keycloak["Keycloak OAuth2 / OIDC Issuer"]
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
            TenantCtx["TenantContext Holder"]
            ProfileEngine["Engine de Perfiles de Integración"]
            DomainModel["Modelos Canónicos de Dominio (Vehicle, SalesOrder, Customer)"]
            JSLTEngine["Transformador JSLT / JsonPath"]
            OutboxHandler["Outbox Publisher (ShedLock)"]
            InboxHandler["Inbox Consumer (Idempotencia / Deduplicación)"]
        end

        subgraph GenericAdapters["Motor de Adaptadores Genéricos Declarativos (Zero-Code)"]
            GenericJdbc["GenericJdbcAdapter\n(Extracción BD Parametrizada)"]
            GenericRest["GenericRestAdapter\n(Extracción API HTTP/REST)"]
            SqlValidator["SqlSecurityValidator\n(Guardrail AST JSqlParser)"]
            AuthCache["OAuth2TokenCacheManager\n(Caché Hilo-Seguro de JWT OIDC)"]
        end
    end

    subgraph InfraLayer["Capa de Infraestructura & Persistencia"]
        MySQL[("MySQL 8.4 DB\n- Profiles\n- Outbox\n- Inbox\n- Domain Data")]
        Redis[("Redis 7.4 Cache\n- Cached Profiles\n- Sessions")]
        Kafka[("Apache Kafka 3.8.1\n- Topic: integration-profile.events")]
    end

    subgraph ExternalSystems["Sistemas & Fuentes Externas"]
        SAPDB["SAP HANA / DBs Relacionales (JDBC)"]
        SAPOData["SAP S/4HANA OData / APIs REST (OIDC/Bearer/API Key)"]
    end

    WebApp -->|"HTTP Request + Bearer JWT"| GW
    Keycloak -->|"Public Keys (JWKS)"| JWTSec
    GW --> JWTSec --> TenantExt --> HeaderInj
    HeaderInj -->|"HTTP + Header X-Tenant-ID"| RESTApi
    RESTApi --> TenantCtx
    TenantCtx --> DomainModel & ProfileEngine

    ProfileEngine -->|"Configura Extracción"| GenericAdapters
    GenericJdbc --> SqlValidator -->|"Query SELECT Validada"| SAPDB
    GenericRest --> AuthCache -->|"Request + Bearer JWT / API Key"| SAPOData

    SAPDB & SAPOData -->|"Filas / Payloads JSON Extraídos"| JSLTEngine
    JSLTEngine --> DomainModel
    DomainModel -->|"Tx Commit (Data + Event)"| MySQL

    OutboxHandler -->|"Polling (Locks via ShedLock)"| MySQL
    OutboxHandler -->|"Publica Eventos Canónicos"| Kafka
    Kafka -->|"Consume Eventos Inbound"| InboxHandler
    InboxHandler -->|"Idempotencia / Deduplicación"| MySQL
    ProfileEngine -->|"Cache Consultas"| Redis
```

---

## 3. Patrones Arquitectónicos Clave

### 3.1. Multitenancy Estricto & Seguridad en Gateway
- **Aislamiento por Tenant**: Cada operación de dominio requiere un `tenant_id` obligatorio activo en el hilo de ejecución (`TenantContext`).
- **Seguridad en Borde (Spring Cloud Gateway)**:
  - El Gateway valida tokens OAuth2/JWT emitidos por **Keycloak**.
  - Extrae la claim `tenant_id` del payload del JWT de forma segura.
  - Elimina cualquier cabecera `X-Tenant-ID` maliciosa enviada por el cliente y reinyecta `X-Tenant-ID: <tenant_id>` validada hacia el backend.

### 3.2. Motor Declarativo de Adaptadores Genéricos (Zero-Code Adapters)
- **Extracción de BD sin Código (`GenericJdbcAdapter`)**: Permite integrar bases de datos externas (SAP HANA, SQL Server, Oracle, PostgreSQL, MySQL) mediante configuración de perfiles en metadatos, evitando compilar clases Java específicas.
- **Extracción de APIs REST/OData (`GenericRestAdapter`)**: Soporta endpoints HTTP dinámicos con plantillas de parámetros delta (`{lastSyncWithBuffer}`), extracción por JsonPath y formatos de fecha configurables.
- **Guardrail de Seguridad SQL (`SqlSecurityValidator`)**:
  - Analizador sintáctico AST (**JSqlParser 4.9**) que garantiza estrictamente ejecuciones de tipo `SELECT`.
  - Rechaza cualquier operación DML/DDL (`INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `EXEC`), multi-statements concatenados con `;` y accesos a tablas de catálogo del sistema (`information_schema`, `sys`, `mysql`, etc.).
- **Gestor de Autenticación de APIs (`OAuth2TokenCacheManager`)**:
  - Administra la autenticación dinámica contra APIs externas soportando **OIDC / OAuth2 Client Credentials**, **Bearer Token**, **Basic Auth** y **API Key**.
  - Mantiene una caché hilo-segura (*thread-safe*) en memoria de tokens de acceso con renovación automática proactiva antes de la expiración.

### 3.3. Arquitectura Hexagonal (Puertos y Adaptadores)
- **Desacoplamiento Total**: Los microservicios de dominio (Customer, Vehicle, SalesOrder) no poseen acoplamiento con SAP, SIGO, SOAP, REST o Kafka.
- **Modelos Canónicos**: Los dominios internos operan exclusivamente con modelos de dominio canónicos y eventos propios.
- **Adaptadores Intercambiables**: La integración con proveedores externos se realiza mediante adaptadores independientes y genéricos.

### 3.4. Transactional Outbox e Inbox Patterns
- **Garantía de Consistencia Eventual**:
  - **Outbox**: La creación de entidades de negocio y la escritura del evento en la tabla `outbox_events` se efectúan dentro de la misma transacción relacional MySQL.
  - **Outbox Publisher**: Polling asíncrono optimizado con bloqueos distribuidos **ShedLock** para publicar eventos en **Apache Kafka** (`integration-profile.events`).
  - **Inbox**: Ingesta de mensajes entrantes registrados previamente en `inbox_events` para garantizar **idempotencia**, **deduplicación**, auditoría y soporte para Dead Letter Queues (DLQ).

---

## 4. Stack Tecnológico

| Capa / Componente | Tecnología | Descripción / Responsabilidad |
| --- | --- | --- |
| **Lenguaje & Runtime** | Java 21 / OpenJDK | Runtime moderno con Virtual Threads y mejoras de rendimiento. |
| **Framework Principal** | Spring Boot 3.4.5 | Framework core de la aplicación backend. |
| **API Gateway** | Spring Cloud Gateway | Proxy inverso, enrutamiento, resiliencia y validación de seguridad en el borde. |
| **Seguridad & IAM** | Keycloak 26.x + Spring Security OAuth2 | Proveedor de identidad y servidor de recursos JWT. |
| **Seguridad SQL** | JSqlParser 4.9 | Validador sintáctico AST para asegurar consultas de solo lectura (`SELECT`). |
| **Base de Datos Relacional** | MySQL 8.4 | Persistencia multitenant de perfiles, modelos canónicos, Outbox e Inbox. |
| **Migración de DB** | Flyway | Gestión y control de versiones de esquemas SQL (`V1__create_integration_profile.sql`). |
| **Event Broker** | Apache Kafka 3.8.1 (KRaft) | Event bus distribuido para comunicación asíncrona desacoplada. |
| **Caché y Estado** | Redis 7.4 + Token Cache | Almacenamiento en caché de perfiles de integración activos y tokens OIDC/OAuth2. |
| **Transformación de Datos** | JSLT (Schibsted) + JsonPath | Motor de mapeo y transformación declarativa de JSON/Payloads. |
| **Resiliencia & Coordinación** | Resilience4j + ShedLock | Circuit breaker, rate limiting y coordinación distribuida de tareas scheduled. |
