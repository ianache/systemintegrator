# Diseño: Gateway OAuth2 con Keycloak y tenant seguro

## Objetivo

Agregar un middleware de entrada basado en Spring Cloud Gateway que proteja la API de integración con OAuth2/JWT, valide tokens emitidos por Keycloak QA y propague el tenant autenticado hacia la aplicación Spring Boot.

## Alcance

- Crear un módulo Maven independiente en `gateway/`.
- Usar Spring Cloud Gateway WebFlux como punto de entrada público.
- Validar JWT mediante Spring Security OAuth2 Resource Server.
- Configurar el issuer mediante `KEYCLOAK_ISSUER_URI`, con valor por defecto únicamente en el perfil `qa-e2e`:
  `https://oauth2.qa.comsatel.com.pe/realms/microservicios`.
- Extraer el claim `tenant_id` como UUID.
- Sobrescribir cualquier `X-Tenant-ID` proporcionado por el cliente con el valor autenticado.
- Rechazar solicitudes sin token, con token inválido o sin `tenant_id` válido.
- Enrutar `/api/**` al servicio `app` dentro de Compose.
- Añadir healthcheck y configuración del servicio `middleware`.
- Mantener el acceso directo a la aplicación protegido por su filtro existente de tenant.

## Fuera de alcance

- No crear un Keycloak local.
- No administrar usuarios, clientes ni credenciales de Keycloak desde el repositorio.
- No guardar usuario/contraseña QA en archivos versionados ni logs.
- No implementar autorización por roles o permisos de negocio.
- No modificar el dominio, persistencia o contrato REST existente.
- No implementar todavía el runner E2E externo contra Keycloak QA; se dejarán contratos y pruebas deterministas locales para el Gateway.

## Arquitectura y flujo

```text
Cliente
  -> middleware:8081
       1. BearerTokenAuthenticationFilter valida firma, issuer y expiración
       2. TenantClaimGatewayFilter obtiene authentication.token.attributes[tenant_id]
       3. elimina X-Tenant-ID del cliente y escribe el tenant autenticado
       4. reenvía /api/** a http://app:8080
  -> app:8080
       TenantFilter valida UUID y conserva el contexto por request
```

El Gateway tendrá dos filtros explícitos. Spring Security resolverá el JWT y devolverá `401` ante credenciales ausentes o inválidas. El filtro de tenant devolverá `403` cuando el token autenticado no incluya un `tenant_id` UUID válido. El header de salida será generado por el Gateway, nunca aceptado como autoridad del cliente.

## Configuración

El módulo Gateway utilizará estas variables:

- `APP_URI`: destino interno de la aplicación, por defecto `http://app:8080`.
- `KEYCLOAK_ISSUER_URI`: issuer OIDC; obligatorio en ejecución protegida.
- `GATEWAY_PORT`: puerto publicado, por defecto `8081`.

El Compose añadirá `app` y `middleware`, con `middleware` dependiente de MySQL, Redis, Kafka y `app` saludable. El servicio `app` recibirá `SPRING_PROFILES_ACTIVE=test` solo para pruebas; la ejecución normal no usará credenciales de QA.

## Pruebas

El Gateway tendrá pruebas deterministas sin red externa:

1. Una solicitud sin Bearer token devuelve `401`.
2. Un JWT inválido devuelve `401`.
3. Un JWT válido con `tenant_id` UUID reenvía el request y sobrescribe un `X-Tenant-ID` falso.
4. Un JWT válido sin `tenant_id` devuelve `403`.
5. Un `tenant_id` mal formado devuelve `403`.
6. La configuración enruta `/api/**` al URI del servicio `app`.

La validación del issuer y la firma se probará con un issuer/JWK local controlado por la prueba. Ninguna prueba predeterminada contactará el Keycloak QA.

## Criterios de aceptación

- `mvn test` del módulo Gateway pasa sin red externa.
- `docker compose config --quiet` pasa sin secretos renderizados desde archivos versionados.
- `docker compose up -d mysql redis kafka app middleware` levanta servicios saludables cuando se proporciona una imagen construible para la aplicación.
- Una llamada con JWT válido al Gateway alcanza la aplicación con el tenant del token.
- Una llamada con header `X-Tenant-ID` manipulado no puede cambiar el tenant efectivo.
- La configuración QA queda activada solo mediante variables/perfil explícito.
