# Diseño: E2E opcional Gateway + Keycloak QA

## Objetivo

Validar el flujo real de autenticación y multitenancy a través del middleware Spring Cloud Gateway usando el Keycloak QA existente, sin introducir secretos ni depender de Keycloak para las pruebas deterministas.

## Alcance

- Añadir una prueba E2E opt-in al módulo `e2e/`.
- Solicitar un token al realm `microservicios` mediante configuración externa.
- Invocar el Gateway en `http://localhost:8081` y verificar autenticación, propagación de tenant y enrutamiento hacia la aplicación.
- Mantener las pruebas predeterminadas sin red externa y sin credenciales.
- Documentar variables, precondiciones, ejecución y limpieza.

## Configuración segura

La prueba solo se ejecutará cuando `QA_E2E_ENABLED=true`. Las siguientes variables serán configurables, con valores no sensibles únicamente para URLs y defaults operativos:

- `KEYCLOAK_ISSUER_URI`, por defecto `https://oauth2.qa.comsatel.com.pe/realms/microservicios`.
- `KEYCLOAK_CLIENT_ID`, por defecto `admin-cli`.
- `KEYCLOAK_USERNAME` y `KEYCLOAK_PASSWORD`, obligatorias y nunca versionadas.
- `GATEWAY_BASE_URL`, por defecto `http://localhost:8081`.

La implementación no imprimirá el password, el access token ni headers de autorización. El token endpoint se derivará del issuer (`/protocol/openid-connect/token`) y se usará únicamente con HTTPS.

## Flujo

1. Comprobar que el perfil QA está explícitamente habilitado.
2. Solicitar un token con password grant al cliente configurable.
3. Decodificar localmente el payload JWT sin registrar el token y exigir un claim `tenant_id` con formato UUID.
4. Crear un perfil mediante el Gateway enviando un `X-Tenant-ID` falso.
5. Verificar que la operación alcanza la aplicación y que el evento Kafka corresponde al tenant del claim, no al header manipulado.
6. Verificar `401` sin Bearer token y `401` con token inválido.
7. Verificar `403` si el token no contiene un `tenant_id` UUID válido, cuando el entorno permita obtener ese token.

## Criterios de aceptación

- Sin `QA_E2E_ENABLED=true`, la suite predeterminada no contacta Keycloak.
- Con QA habilitado y las credenciales proporcionadas por entorno, la prueba obtiene token y valida el flujo Gateway → aplicación → Kafka.
- El `X-Tenant-ID` enviado por el cliente no puede alterar el tenant autenticado.
- Fallos de precondición (servicios no disponibles, client id no autorizado o usuario sin `tenant_id`) producen mensajes accionables sin exponer secretos.
- `docker compose config --quiet` permanece válido.
- La documentación permite ejecutar y limpiar el stack sin guardar credenciales.

## Fuera de alcance

- Crear o modificar usuarios, clientes, roles o mappers en Keycloak QA.
- Crear un Keycloak local.
- Versionar credenciales, tokens o respuestas completas de autenticación.
- Reemplazar las pruebas deterministas existentes.
