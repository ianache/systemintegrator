# Informe MVP SIGO Vehicle

## Estado

Implementado el vertical inicial de SIGO Vehicle:

- modelo canónico `Vehicle` tenant-aware y endpoint REST;
- migración MySQL para vehículos, Outbox e Inbox;
- escritura transaccional de `vehicle.created` en Outbox;
- publisher Kafka desacoplado y puerto/adaptador HTTP SIGO;
- aceptación idempotente de eventos mediante Inbox;
- pruebas unitarias de Vehicle e Inbox y verificación del filtro tenant.

## Verificación

- `mvn -q -Dtest=TenantFilterTest,VehicleTest,InboxProcessorTest test`: PASS.
- `mvn -q test "-Dtest=!IntegrationProfileEndToEndTest,!IntegrationProfilePersistenceAdapterTest"`: PASS; 49 tests ejecutados sin contenedores.
- `mvn -q -DskipTests compile`: PASS.

## Bloqueo de contenedores

Docker no está instalado ni disponible en PATH (`docker : el término 'docker' no se reconoce`). Además, Testcontainers confirmó que no existe un entorno Docker válido. Por ello quedaron pendientes `IntegrationProfileEndToEndTest` y `IntegrationProfilePersistenceAdapterTest`, que requieren MySQL Testcontainers; no se sustituyeron por una base local ni por un servicio del desarrollador.

## Pendientes conocidos

- Ejecutar las pruebas MySQL/Kafka/WireMock end-to-end cuando Docker esté disponible.
- Completar la persistencia de todos los campos avanzados del perfil de integración (endpoint, credencial referenciada, mapping y políticas) en una siguiente iteración.
- Conectar el polling/publicación de Outbox y el consumidor SIGO a la configuración persistida del perfil.
