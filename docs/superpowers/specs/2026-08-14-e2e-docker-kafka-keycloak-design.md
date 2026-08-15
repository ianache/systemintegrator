# Diseño: E2E con Docker, Kafka y Keycloak

**Fecha:** 2026-08-14  
**Estado:** Aprobado por el usuario  
**Fuente:** `docs/scopes/PRD.md` y solicitud del usuario

## Objetivo

Completar las pruebas E2E pendientes del primer slice de perfiles de integración y entregar un entorno reproducible con Docker Compose que incluya MySQL, Redis, Apache Kafka, la aplicación Spring Boot y un middleware de entrada. El middleware será Spring Cloud Gateway y validará OAuth2/JWT contra el Keycloak QA existente.

## Infraestructura

`compose.yaml` levantará estos servicios:

- `mysql:8.x`: base de datos de la aplicación, con volumen y healthcheck.
- `redis:7.x`: cache disponible para la aplicación, con healthcheck.
- `kafka`: broker Apache Kafka para eventos, con configuración de un solo nodo adecuada para desarrollo y E2E.
- `app`: aplicación Spring Boot, dependiente de MySQL, Redis y Kafka saludables.
- `middleware`: Spring Cloud Gateway, expuesto al host y dependiente de `app`; validará JWT mediante el issuer de Keycloak QA.

Keycloak no se ejecutará localmente. Se usará:

```text
https://oauth2.qa.comsatel.com.pe/realms/microservicios
```

La URL será configurable mediante variables de entorno para permitir cambiar de ambiente sin editar archivos versionados. Las credenciales proporcionadas para QA no se almacenarán en el repositorio ni se imprimirán en logs.

## Flujo de seguridad y multitenancy

```text
Cliente con Bearer JWT
        ↓
Spring Cloud Gateway valida issuer, firma y expiración
        ↓
Gateway copia claim tenant_id a X-Tenant-ID
        ↓
App valida UUID y establece TenantContext
        ↓
Caso de uso y persistencia tenant-scoped
```

El Gateway rechazará tokens inválidos o sin `tenant_id`. La aplicación también rechazará solicitudes sin un `X-Tenant-ID` válido, incluso si se accede directamente sin pasar por el Gateway. El tenant no se aceptará desde el body.

## Eventos Kafka

Las operaciones exitosas de creación, actualización y desactivación publicarán un evento en `integration-profile.events` después del commit de la transacción de persistencia.

El contrato incluirá:

- `eventId` único.
- `eventType`: `IntegrationProfileCreated`, `IntegrationProfileUpdated` o `IntegrationProfileDeactivated`.
- `profileId`.
- `tenantId`.
- `occurredAt` en UTC.
- datos necesarios para identificar el nuevo estado del perfil.

La aplicación utilizará un productor Kafka y un consumidor de prueba separado. Las E2E esperarán el evento correspondiente usando `eventId`/`profileId` y `tenantId`, evitando depender únicamente del orden global del tópico.

## Redis

Redis formará parte del arranque y de los healthchecks del entorno. Se configurará en la aplicación mediante `spring.data.redis`. No se introducirá cache de negocio en este slice salvo que el código existente lo requiera; la cobertura inicial verificará conectividad y disponibilidad del servicio.

## Pruebas E2E

La suite cubrirá:

1. arranque limpio de la aplicación con MySQL, Redis y Kafka;
2. acceso mediante Gateway con JWT de Keycloak;
3. rechazo de solicitudes sin autenticación o sin tenant;
4. CRUD de `IntegrationProfile`;
5. aislamiento entre dos tenants;
6. desactivación lógica;
7. publicación y recepción de eventos Kafka;
8. migración Flyway desde una base vacía.

Las pruebas deterministas de contrato podrán usar tokens de prueba locales. Las pruebas contra Keycloak QA se identificarán mediante un perfil separado y variables de entorno, de modo que la suite local no falle únicamente por indisponibilidad de la red externa.

## Errores y operación local

- La configuración sensible se leerá de `.env` o variables del proceso y se excluirá mediante `.gitignore`.
- Los servicios tendrán healthchecks y condiciones de dependencia explícitas.
- Kafka usará un tópico inicializable por configuración o por una etapa de arranque idempotente.
- Las respuestas HTTP conservarán el formato `ProblemDetail` del slice aprobado.
- Se documentarán comandos para levantar el stack, ejecutar E2E, inspeccionar logs y detenerlo.

## Fuera de alcance

No se añadirá un Keycloak local, Kafka Connect, Vault, DLQ productiva, Outbox completa, conectores SAP/SIGO ni lógica de cache funcional no requerida por las E2E.
