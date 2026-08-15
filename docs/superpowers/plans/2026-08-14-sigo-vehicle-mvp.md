# Plan: MVP de integración SIGO Vehicle

## Objetivo

Construir un vertical mínimo y verificable para integrar vehículos con SIGO, manteniendo el dominio independiente del protocolo, endpoint, credenciales y tecnología de mensajería.

## Alcance aprobado

- Tenant identificado desde JWT y propagado al contexto, persistencia y eventos.
- `vehicle-service` con un modelo canónico de vehículo y referencias a marca/modelo.
- Perfil de integración configurable por tenant: fuente SIGO, endpoint, referencia de credencial, mapeo, política de sincronización, reintentos/backoff, rate limit y estado activo.
- Outbox transaccional en MySQL: cambio de negocio y evento canónico en la misma transacción.
- Publicación asíncrona hacia Kafka mediante un adaptador, sin llamadas externas dentro de la transacción de dominio.
- Adaptador SIGO aislado detrás de un puerto, inicialmente probado con WireMock.
- Inbox idempotente para deduplicación, auditoría, reintentos y estado de procesamiento.
- Prueba end-to-end del flujo `vehicle event -> outbox -> Kafka -> adapter SIGO -> audit/inbox`.

## Fuera de alcance

- SAP y otros sistemas externos.
- Implementación completa de todos los protocolos.
- Vault real, UI administrativa y despliegue productivo.
- Sincronización bidireccional completa.
- Dominios Customer y SalesOrder.

## Secuencia de implementación

1. Revisar la base de Slice 1 y definir contratos canónicos, puertos y estados de outbox/inbox.
2. Añadir el modelo y persistencia de Vehicle respetando tenant isolation y migraciones Flyway.
3. Añadir configuración SIGO al perfil sin acoplar el dominio a HTTP, Kafka o credenciales reales.
4. Implementar outbox transaccional y publicación Kafka con reintentos observables.
5. Implementar el adaptador SIGO y su mapeo desde/hacia el modelo canónico usando WireMock en pruebas.
6. Implementar inbox idempotente, auditoría, reintentos y tratamiento de mensajes no procesables.
7. Cubrir unitariamente dominio/mapeos, MVC/tenant filter, persistencia y servicios con dobles locales.
8. Añadir pruebas de integración con Testcontainers MySQL/Kafka y WireMock; estas pruebas se ejecutarán cuando exista Docker.

## Verificación

- Ejecutar `mvn test` y todos los tests que no requieran contenedores en el entorno actual.
- Intentar identificar Docker antes de ejecutar la suite containerizada; si no está disponible, registrar el comando, el error y exactamente qué pruebas quedaron pendientes.
- Verificar que ningún test arranque una base de datos local implícita ni dependa de servicios del desarrollador.
- Revisar cambios limitados a los archivos del MVP SIGO, configuración de pruebas y README/documentación necesaria.

## Entregables

- Código del vertical SIGO Vehicle.
- Migraciones, configuración y pruebas.
- README actualizado con prerrequisitos y comandos.
- Informe de implementación, pruebas ejecutadas y bloqueo de Docker, si aplica.
- Commit(es) descriptivos en la rama de trabajo.
