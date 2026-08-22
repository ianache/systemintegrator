# Guía Operativa: Reprocesamiento de Mensajes en Dead Letter Queue (DLQ Replay)

Este documento detalla el procedimiento operativo, arquitectura, ciclo de vida de errores y mecanismos disponibles para inspeccionar y reprocesar eventos en estado **`DEAD_LETTER`** dentro de la plataforma de integración.

---

## 1. Ciclo de Vida y Causas de Transición a `DEAD_LETTER`

Cuando un evento es consumido desde el bus Apache Kafka por el `KafkaInboxListener`:

1. El evento se registra en `integration_inbox` con estado inicial `RECEIVED`.
2. El `InboxProcessor` delega la ejecución al `OutboundEventDispatcher`.
3. Si el despacho falla (por ejemplo, timeout de red, API remota retornando errores `4xx`/`5xx` o el Circuit Breaker abriéndose tras fallos repetidos):
   - El consumidor de Spring Kafka reintenta la entrega según la política configurada (hasta 10 intentos).
   - Si los reintentos se agotan, `InboxProcessor` ejecuta:
     1. Actualiza `integration_inbox.status = 'DEAD_LETTER'` y guarda el motivo en `last_error`.
     2. `DeadLetterQueuePublisher` envía una copia del evento al tópico de mensajes muertos en Kafka (`integration.<domain>.events.dlq`).

---

## 2. Mecanismos de Reprocesamiento (Replay)

### 2.1. Método Recomendado: API REST Administrativa (`/api/v1/inbox/dlq/replay`)

La plataforma expone un endpoint administrativo transaccional para reprocesar en bloque todos los eventos fallidos del tenant autenticado.

#### Detalle de la Petición:
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/inbox/dlq/replay` (o `http://localhost:8081/api/v1/inbox/dlq/replay` a través del Gateway)
* **Headers**:
  ```http
  Authorization: Bearer <TOKEN_ADMIN_KEYCLOAK>
  X-Tenant-ID: 11111111-1111-1111-1111-111111111114
  Content-Type: application/json
  ```

#### Comportamiento del Servicio (`DeadLetterQueueReplayService`):
1. Recupera los registros con `status = 'DEAD_LETTER'` asociados al `tenantId`.
2. Vuelve a ejecutar la transformación JSLT y el despacho HTTP con `HttpOutboundClient`.
3. **Si el envío es exitoso**:
   - Actualiza el estado a `PROCESSED`.
   - Registra la marca de tiempo `processed_at = NOW()`.
4. **Si el envío vuelve a fallar**:
   - Mantiene el estado en `DEAD_LETTER`.
   - Actualiza `last_error` con la causa más reciente.
5. Retorna un resumen con el conteo de ejecuciones.

#### Ejemplo de Respuesta (`HTTP 200 OK`):
```json
{
  "total": 71,
  "success": 71,
  "failed": 0
}
```

---

### 2.2. Método Alternativo: Reprocesamiento Directo en Base de Datos

En entornos de mantenimiento o durante tareas de depuración masiva:

1. **Restablecer el estado en `integration_inbox`**:
   ```sql
   UPDATE integration_inbox 
   SET status = 'PENDING', attempts = 0, last_error = NULL 
   WHERE tenant_id = UNHEX(REPLACE('11111111-1111-1111-1111-111111111114','-',''))
     AND status = 'DEAD_LETTER';
   ```

2. **Reencolar la publicación en `integration_outbox`**:
   ```sql
   UPDATE integration_outbox 
   SET status = 'PENDING', attempts = 0, last_error = NULL, available_at = NOW() 
   WHERE id IN (
       SELECT event_id FROM integration_inbox 
       WHERE tenant_id = UNHEX(REPLACE('11111111-1111-1111-1111-111111111114','-',''))
         AND status = 'PENDING'
   );
   ```

---

## 3. Verificaciones Previas al Reprocesamiento

Antes de disparar el replay, validar:
1. **Salud del Endpoint Destino**: Verificar que la API externa se encuentre operativa y respondiendo.
2. **Corrección de Datos / Catálogos**: Si el fallo se debió a un `400 Bad Request` por esquemas o IDs inexistentes en el sistema destino, corregir los datos en origen o el mapeo en el script JSLT del perfil.
3. **Estado del Circuit Breaker**: Si el Circuit Breaker está abierto (`OPEN`), esperar que transcurra el tiempo de enfriamiento (10 segundos) para que entre en estado `HALF_OPEN` y permita nuevas llamadas.
