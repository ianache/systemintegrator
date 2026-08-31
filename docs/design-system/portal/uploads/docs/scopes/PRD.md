PRD — Plataforma Multitenant de Integración Flexible

Versión: 2.0
Fecha: Agosto 2026
Estado: Ready for Implementation
Destinatario: Codex
Backend: Java 21 + Spring Boot 3.x
Base de datos: MySQL 8.x
Arquitectura: Multitenant · Event-Driven · Hexagonal · Inbox/Outbox · Connector/Adapter

---

1. Objetivo

Construir una plataforma de integración Marca Blanca Multitenant capaz de conectar los dominios internos de la plataforma con fuentes externas heterogéneas sin acoplar los microservicios de negocio a sistemas o protocolos específicos.

La plataforma deberá permitir configurar, por tenant:

- dominio de negocio;
- fuente externa;
- protocolo/conector;
- adaptador;
- dirección de sincronización;
- Source of Truth;
- endpoint;
- autenticación;
- mapping;
- transformaciones;
- política de sincronización;
- retry/backoff;
- rate limiting;
- activación/desactivación.

Los primeros sistemas externos serán:

SAP

- Customer
- SalesOrder

SIGO

- Vehicle
- VehicleBrand
- VehicleModel

La arquitectura deberá permitir incorporar posteriormente nuevas fuentes REST, SOAP, JSON-RPC, Kafka, JDBC u otros mecanismos sin modificar el core de integración.

---

2. Principios arquitectónicos

2.1 Multitenancy

Toda operación debe estar asociada explícitamente a un "tenant_id".

El tenant se obtiene de:

JWT
 ↓
tenant_id claim
 ↓
Spring Cloud Gateway
 ↓
X-Tenant-ID
 ↓
TenantContext
 ↓
Application / Persistence / Events

Está prohibido ejecutar operaciones de dominio tenant-aware sin tenant activo.

---

2.2 Source of Truth configurable

Cada "IntegrationProfile" define:

enum SourceOfTruth {
    PLATFORM,
    EXTERNAL,
    SHARED
}

No se asumirá que la plataforma es propietaria de los datos maestros.

---

2.3 Dirección configurable

enum SyncDirection {
    INBOUND,
    OUTBOUND,
    BIDIRECTIONAL
}

La dirección se configura por:

Tenant + Domain + External Source

---

2.4 Desacoplamiento

Los servicios:

customer-service
vehicle-service
sales-order-service

NO deben conocer:

SAP
SIGO
REST
SOAP
Kafka externo
credenciales externas
URLs externas

Solo trabajan con sus modelos de dominio y eventos canónicos.

---

2.5 Event Driven

La integración debe ser asíncrona siempre que el proceso de negocio no requiera una respuesta inmediata.

Kafka será el bus principal de integración.

---

2.6 Transactional Outbox

Ningún servicio de dominio realizará llamadas a sistemas externos dentro de su transacción de negocio.

Business Transaction
      │
      ├── Domain Data
      │
      └── Outbox Event
              │
             COMMIT

Ambos registros deben formar parte de la misma transacción MySQL.

---

2.7 Inbox

Los mensajes provenientes de fuentes externas deberán registrarse antes de afectar el dominio.

El Inbox proporcionará:

- idempotencia;
- deduplicación;
- auditoría;
- retry;
- trazabilidad;
- reprocesamiento;
- DLQ.

---

3. Stack tecnológico obligatorio

Área| Tecnología
Lenguaje| Java 21
Framework| Spring Boot 3.x
Cloud| Spring Cloud
Persistence| Spring Data JPA / Hibernate
Database| MySQL 8.x
Migration| Flyway
Messaging| Apache Kafka
Cache| Redis 7.x
Seguridad| Spring Security
IAM| Keycloak 26.x
Secrets| HashiCorp Vault
Resilience| Resilience4j
HTTP| Spring RestClient
Testing| JUnit 5
Mock HTTP| WireMock
Integration Testing| Testcontainers
Build| Maven
Containers| Docker
SCM| GitLab

No introducir tecnologías alternativas sin ADR aprobado.

---

4. Arquitectura lógica

                       USERS
                         │
                         ▼
                 Spring Cloud Gateway
                         │
                 JWT / tenant_id
                         │
       ┌─────────────────┼─────────────────┐
       │                 │                 │
       ▼                 ▼                 ▼
 customer-service   vehicle-service   sales-order-service
       │                 │                 │
       └─────────────�