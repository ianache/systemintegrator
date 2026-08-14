# Diseño del Slice 1: Integration Profile Multitenant

**Fecha:** 2026-08-14  
**Estado:** Aprobado por el usuario  
**Fuente:** `docs/scopes/PRD.md`

## Objetivo

Construir el primer slice vertical de la plataforma de integración: una aplicación Spring Boot ejecutable que permita administrar perfiles de integración aislados por tenant. El slice debe establecer las fronteras hexagonales, el contexto multitenant y la persistencia sobre MySQL para soportar los siguientes incrementos del MVP.

Este slice no implementará todavía Kafka, Outbox, Inbox, conectores externos, credenciales, endpoints ni mappings.

## Arquitectura

Se utilizará una arquitectura hexagonal modular en una sola aplicación desplegable:

- `domain`: entidades, enums, reglas e interfaces de repositorio.
- `application`: casos de uso y puertos de entrada/salida.
- `adapter.in.web`: controladores REST, DTOs y manejo de errores.
- `adapter.out.persistence`: entidades JPA, repositorios y mapeadores.
- `infrastructure`: configuración Spring, filtro de tenant y transacciones.

Flujo:

```text
HTTP X-Tenant-ID
        ↓
TenantFilter
        ↓
TenantContext
        ↓
REST Controller
        ↓
Use Case
        ↓
Domain
        ↓
Persistence Adapter
        ↓
MySQL
```

Reglas arquitectónicas:

- Toda operación de perfil exige un tenant activo.
- El `tenant_id` se obtiene del contexto validado y no del body.
- La API no expone entidades JPA directamente.
- El dominio no depende de Spring, JPA ni HTTP.
- Los perfiles se identifican mediante UUID.
- La desactivación es lógica mediante `active = false`.

## Modelo de datos

La tabla `integration_profile` tendrá:

- `id`: UUID, clave primaria.
- `tenant_id`: UUID, obligatorio.
- `business_domain`: texto, por ejemplo `CUSTOMER`.
- `external_source`: texto, por ejemplo `SAP`.
- `sync_direction`: `INBOUND`, `OUTBOUND` o `BIDIRECTIONAL`.
- `source_of_truth`: `PLATFORM`, `EXTERNAL` o `SHARED`.
- `active`: booleano.
- `created_at` y `updated_at`: timestamps UTC.
- `version`: control optimista.

Restricciones e índices:

- `tenant_id` es obligatorio.
- Solo puede existir un perfil activo por `tenant_id + business_domain + external_source`.
- Se crearán índices por tenant y estado.
- Credenciales, endpoints y mappings quedan fuera del alcance de este slice.

## Contrato REST

```text
POST   /api/v1/integration-profiles
GET    /api/v1/integration-profiles
GET    /api/v1/integration-profiles/{id}
PUT    /api/v1/integration-profiles/{id}
DELETE /api/v1/integration-profiles/{id}
```

El `DELETE` realizará desactivación lógica. Las respuestas usarán DTOs y no entidades JPA. Los errores tendrán formato uniforme basado en `ProblemDetail`.

## Tenant y errores

- `TenantFilter` rechazará toda petición sin `X-Tenant-ID`.
- `X-Tenant-ID` debe contener un UUID válido.
- `TenantContext` se limpiará siempre al finalizar la petición.
- Las consultas de persistencia filtrarán explícitamente por `tenant_id`.
- Un perfil de otro tenant responderá como `404` para evitar filtrar su existencia.

Códigos previstos:

- `400`: payload inválido o enum desconocido.
- `404`: perfil inexistente o perteneciente a otro tenant.
- `409`: duplicado activo o conflicto de versión.
- `422`: regla de negocio inválida.
- `500`: error inesperado, con correlación en logs.

## Pruebas y criterios de éxito

Pruebas requeridas:

- Unitarias para reglas de dominio y casos de uso.
- Web tests para headers, payloads y códigos HTTP.
- Integración con MySQL mediante Testcontainers.
- Aislamiento explícito entre dos tenants.
- Migración Flyway desde una base vacía.

El slice se considerará terminado cuando:

1. La aplicación arranque con Maven.
2. Flyway cree el esquema correctamente.
3. Toda operación de perfil sin tenant sea rechazada.
4. Un tenant no pueda leer ni modificar perfiles de otro.
5. El CRUD completo funcione mediante REST.
6. La desactivación lógica respete la unicidad de perfiles activos.
7. La suite de pruebas pase en una ejecución limpia.

## Evolución prevista

Los siguientes slices podrán añadir Outbox, eventos canónicos, Inbox, configuración de endpoints y autenticación sin mover las dependencias del dominio hacia Kafka, HTTP, Vault o sistemas externos.
