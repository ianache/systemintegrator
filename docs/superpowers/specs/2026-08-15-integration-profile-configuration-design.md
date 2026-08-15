# Diseño: configuración extendida de IntegrationProfile

## Objetivo

Ampliar `IntegrationProfile` para describir cómo se conecta un tenant con una
fuente externa, sin implementar todavía los conectores SAP/SIGO ni el
procesamiento completo de Outbox/Inbox.

El cambio debe mantener funcionando los perfiles existentes que solo contienen
dominio, fuente, dirección y `SourceOfTruth`.

## Alcance

Cada perfil podrá incluir:

| Campo | Tipo | Regla |
|---|---|---|
| `protocol` | enum nullable | `REST`, `SOAP`, `JSON_RPC`, `KAFKA`, `JDBC` |
| `connector` | string nullable | Identificador lógico del conector |
| `adapter` | string nullable | Identificador del adaptador técnico |
| `endpoint` | string nullable | URL o endpoint de conexión; no contiene secretos |
| `credentialRef` | string nullable | Referencia a un secreto externo; nunca el secreto plano |
| `mapping` | JSON nullable | Mapeo de campos de entrada/salida |
| `transformation` | JSON nullable | Reglas de transformación |
| `syncPolicy` | JSON nullable | Configuración de modo, frecuencia o disparador |
| `retryPolicy` | JSON nullable | Intentos y backoff |
| `rateLimitPolicy` | JSON nullable | Límite y ráfaga de solicitudes |

Los documentos JSON se almacenarán como texto JSON validado. No se introducirá
una jerarquía de clases específica para cada conector en esta fase.

## Modelo y persistencia

Se agregará una migración Flyway posterior a `V2` con columnas anulables en
`integration_profile`. Los enums se persistirán como texto. Las configuraciones
JSON se almacenarán en columnas `JSON` de MySQL, conservando `NULL` para
perfiles legacy.

El modelo de dominio conservará estos valores y los expondrá mediante accesores
inmutables. La operación `update` reemplazará la configuración completa y
seguirá incrementando `version` mediante control optimista.

## Contrato HTTP

`POST` y `PUT` aceptarán los nuevos campos. `GET` los devolverá. Un payload
legacy sin campos nuevos seguirá siendo válido.

Validaciones:

1. Si `protocol` es `null`, no se exige configuración de conectividad.
2. Si `protocol` tiene valor, `connector` y `adapter` deben ser no vacíos.
3. `mapping`, `transformation`, `syncPolicy`, `retryPolicy` y
   `rateLimitPolicy`, cuando se proporcionen, deben ser JSON válido.
4. `credentialRef` solo identifica un secreto; no se acepta un campo de
   contraseña ni se registra su contenido en logs o respuestas.
5. `retryPolicy` no puede definir menos de un intento ni backoff negativo.
6. `rateLimitPolicy` no puede definir límites negativos o cero.

Los errores de validación conservarán el formato Problem Details existente.

## Seguridad y multitenancy

El tenant seguirá proviniendo exclusivamente de `TenantContext`, alimentado
por `X-Tenant-ID` en llamadas directas o por el claim `tenant_id` propagado por
el Gateway. Ningún campo del nuevo perfil podrá establecer o sobrescribir el
tenant.

Los valores `credentialRef` y los documentos JSON no se incluirán en logs de
autenticación ni se usarán para transportar credenciales reales.

## Pruebas

Se agregarán pruebas antes de la implementación para:

- aceptar un perfil legacy;
- crear y actualizar un perfil con configuración extendida;
- rechazar un protocolo sin `connector` o `adapter`;
- rechazar JSON de política o mapping inválido;
- conservar el control de versión;
- persistir y recuperar la configuración con MySQL Testcontainers;
- no exponer perfiles de otro tenant;
- devolver `credentialRef` sin ningún secreto asociado.

No se probará todavía una llamada real a SAP, SIGO, Vault o un sistema
externo; esos elementos pertenecen a los siguientes alcances.

## Fuera de alcance

- Implementación de conectores SAP, SIGO, Customer o SalesOrder.
- Ejecución de mapping o transformaciones.
- Dispatcher Outbox, consumidor Inbox, DLQ y reprocesamiento.
- Integración con HashiCorp Vault.
- Retry, rate limiting y circuit breaker en tiempo de ejecución.
