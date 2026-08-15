# Diseño: E2E determinista de API, MySQL y Kafka

## Objetivo

Agregar una suite E2E reproducible para el primer slice de perfiles de integración. La suite validará la API real, la persistencia MySQL y la publicación/consumo de eventos Kafka sin depender de Keycloak QA ni de red externa.

## Alcance

- Crear un módulo Maven independiente `e2e/`.
- Levantar MySQL y Kafka con Testcontainers desde la suite.
- Ejecutar el flujo HTTP contra la aplicación Spring Boot mediante `X-Tenant-ID`.
- Cubrir dos tenants independientes, CRUD, aislamiento, baja lógica y eventos Kafka.
- Esperar eventos por `profileId`, `tenantId` y tipo de evento con timeout acotado.
- Mantener la suite separada de las pruebas unitarias y de la futura suite Gateway + Keycloak QA.

## Fuera de alcance

- No contactar `https://oauth2.qa.comsatel.com.pe`.
- No crear un Keycloak local ni emitir tokens OAuth2.
- No reemplazar la autenticación del Gateway ni modificar el contrato de producción.
- No usar mocks para sustituir MySQL o Kafka en los escenarios principales.

## Arquitectura

El módulo `e2e` arrancará contenedores MySQL y Kafka en una clase base JUnit compartida. Las propiedades dinámicas apuntarán la aplicación al datasource y broker de los contenedores. La aplicación se iniciará con el perfil `test` y el cliente HTTP usará el puerto aleatorio del servidor embebido.

`ApiClient` encapsulará las llamadas REST y deserializará respuestas JSON a DTOs mínimos de prueba. `KafkaEventObserver` creará un consumidor dedicado por prueba o por clase, suscribirá el tópico canónico y recorrerá mensajes hasta encontrar el evento cuyo `profileId`, `tenantId` y tipo coincidan, usando un timeout finito y cerrando el consumidor al terminar.

## Escenarios

1. Crear un perfil para tenant A y verificar respuesta `201`, `id`, `tenantId` y evento `integration-profile.created`.
2. Consultar el perfil con tenant A y verificar los datos persistidos.
3. Consultar el mismo `id` con tenant B y verificar `404` sin fuga de datos.
4. Actualizar el perfil desde tenant A y verificar respuesta y evento de actualización, si el contrato existente lo publica.
5. Desactivar el perfil desde tenant A y verificar que desaparece de la consulta activa y aparece cuando se solicita histórico.
6. Verificar el evento de desactivación y que contiene el tenant correcto.
7. Ejecutar la suite sobre una base vacía para comprobar Flyway y repetirla con volúmenes/containers limpios.

Si el contrato existente no publica un evento para una operación concreta, la prueba validará explícitamente el comportamiento definido por el servicio y no inventará un evento adicional.

## Configuración y limpieza

La suite recibirá `spring.datasource.*`, `spring.kafka.bootstrap-servers` y el tópico mediante `@DynamicPropertySource`. Los contenedores se detendrán automáticamente después de la clase. El runner no dependerá de `compose.yaml`; Compose seguirá siendo el entorno manual de integración y smoke testing.

## Criterios de aceptación

- `mvn -pl e2e -am test` ejecuta la suite determinista cuando Docker está disponible.
- Los escenarios demuestran aislamiento real entre dos tenants.
- El observador Kafka no depende del orden global del tópico ni de sleeps fijos.
- Un entorno limpio aplica Flyway y Kafka desde cero.
- Sin Docker, el comando falla de forma clara indicando la dependencia ambiental, sin convertir la suite en una prueba falsa o ignorada.
