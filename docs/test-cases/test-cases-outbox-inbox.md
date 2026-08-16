# Plan y Casos de Prueba: Transactional Outbox, Idempotent Inbox y Dead Letter Queue (DLQ)

## 1. Información General

| Campo | Valor |
|---|---|
| Módulo | Transactional Outbox & Idempotent Inbox Pipeline |
| Entorno de ejecución | MySQL 8.4 (`integration`), Kafka 3.8.1 (`integration.events`, `integration.events.dlq`) |
| Versión Flyway | V4 (`V4__enhance_outbox_inbox_dlq.sql`) |
| Tablas principales | `integration_outbox`, `integration_inbox`, `integration_inbox_dlq` |
| Esquema de Concurrencia | `FOR UPDATE SKIP LOCKED` en relay de outbox |
| Esquema de Idempotencia | Primary Key `(id, tenant_id)` con deduplicación y manejo de DLQ |

---

## 2. Arquitectura del Flujo

```
+-----------------------------------------------------------------------------------+
|                            DOMAIN BOUNDED CONTEXT                                 |
|                                                                                   |
|  1. Domain Mutation + Outbox INSERT (Atomic Transaction)                          |
|     +------------------------------------------------------------------------+    |
|     |  BEGIN TRANSACTION;                                                    |    |
|     |    INSERT INTO domain_entity (...) VALUES (...);                       |    |
|     |    INSERT INTO integration_outbox (id, tenant_id, ..., status)         |    |
|     |           VALUES (UUID(), UUID(), ..., 'PENDING');                     |    |
|     |  COMMIT;                                                               |    |
|     +------------------------------------------------------------------------+    |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                             OUTBOX RELAY SCHEDULER                                |
|                                                                                   |
|  2. Polling Batch with SKIP LOCKED:                                               |
|     SELECT * FROM integration_outbox                                              |
|     WHERE status = 'PENDING' AND available_at <= NOW(6)                           |
|     ORDER BY available_at ASC LIMIT 50 FOR UPDATE SKIP LOCKED;                    |
|                                                                                   |
|  3. Dispatch to Apache Kafka:                                                     |
|     - SUCCESS -> UPDATE integration_outbox SET status='PUBLISHED',                |
|                  published_at=NOW(6) WHERE id=...                                 |
|     - RETRYABLE ERROR -> attempts++, backoff = initial * 2^(attempts-1)           |
|                  available_at = NOW(6) + backoff, last_error=...                  |
|     - EXCEEDED MAX ATTEMPTS -> status='FAILED', last_error=...                    |
+-----------------------------------------------------------------------------------+
                                         |
                                         v (Kafka Topic: integration.events)
+-----------------------------------------------------------------------------------+
|                             INBOX PROCESSOR & DLQ                                 |
|                                                                                   |
|  4. Idempotent Consumer:                                                          |
|     - Step 4.1: Record Event (INSERT IGNORE into integration_inbox)               |
|       * If ALREADY PROCESSED -> Skip duplicate execution gracefully.              |
|       * If NEW EVENT -> Execute domain consumer lambda:                           |
|         - SUCCESS -> UPDATE integration_inbox SET status='PROCESSED',             |
|                      processed_at=NOW(6) WHERE id=...                             |
|         - FAILURE -> Record in integration_inbox_dlq and forward payload          |
|                      to DLQ Kafka Topic (integration.events.dlq).                 |
+-----------------------------------------------------------------------------------+
```

---

## 3. Matriz de Casos de Prueba Automatizados

| ID | Suite / Test Class | Test Case | Tipo | Descripción y Validación |
|---|---|---|---|---|
| **OIB-01** | `OutboxInboxFlowIntegrationTest` | `shouldRelayOutboxRecordAndProcessInInboxIdempotently` | Integración E2E | Inserta evento en `integration_outbox`, ejecuta relay con mock de Kafka, procesa en `InboxProcessor` verificando ejecución única e idempotencia en reintentos duplicados. |
| **OIB-02** | `OutboxEntityTest` | `shouldCreatePendingEventWithDefaultValues` | Unitario | Valida creación de `OutboxEvent` en estado `PENDING`, `attempts=0`, timestamps y mapeo bidireccional a `OutboxJpaEntity`. |
| **OIB-03** | `OutboxRelaySchedulerTest` | `shouldRelayPendingEventAndMarkPublished` | Unitario | Verifica que eventos pendientes se publiquen a Kafka y su estado transicione a `PUBLISHED` con `publishedAt`. |
| **OIB-04** | `OutboxRelaySchedulerTest` | `shouldHandlePublishingErrorWithBackoff` | Unitario | Simula falla de red en Kafka, verificando incremento de intentos, cálculo exponencial de backoff y persistencia de `lastError`. |
| **OIB-05** | `OutboxRelaySchedulerTest` | `shouldMarkTerminalFailedWhenMaxAttemptsReached` | Unitario | Simula agotamiento de reintentos máximos configurados (`maxAttempts`), verificando transición terminal a `FAILED`. |
| **OIB-06** | `OutboxRelaySchedulerTest` | `shouldSkipRelayWhenDisabled` | Unitario | Comprueba que al desactivar `outbox.relay.enabled=false`, el scheduler no interactúe con el repositorio ni intente despachos. |
| **OIB-07** | `InboxEntityTest` | `shouldCreateInboxJpaEntityWithDefaults` | Unitario | Verifica instanciación de `InboxJpaEntity` en estado `RECEIVED` con `attempts=0` y mapeo a dominio. |
| **OIB-08** | `InboxProcessorTest` | `shouldProcessNewEventSuccessfully` | Unitario | Valida que un evento nuevo se registre en el `InboxStore`, se ejecute la lógica de negocio y se marque como `PROCESSED`. |
| **OIB-09** | `InboxProcessorTest` | `shouldSkipDuplicateAlreadyProcessedEvent` | Unitario | Valida que un evento duplicado ya procesado sea detectado y no vuelva a invocar el consumidor de dominio. |
| **OIB-10** | `InboxProcessorTest` | `shouldForwardToDlqOnDomainFailure` | Unitario | Valida que ante un error irrecuperable en el handler de dominio, el evento sea registrado como `DEAD_LETTER` y enviado al topic DLQ. |

---

## 4. Casos de Prueba Manuales y de Verificación E2E

### OIB-MAN-01: Inserción Atómica y Relay a Kafka

1. **Objetivo:** Verificar que un registro creado en `integration_outbox` sea recogido por el relay y despachado al topic de Kafka.
2. **Precondición:** Servicios `mysql` y `kafka` activos.
3. **Paso 1 (Insertar evento outbox manual):**
   ```sql
   INSERT INTO integration_outbox (
       id, tenant_id, aggregate_type, aggregate_id, event_type, topic, payload, status, attempts, available_at, created_at
   ) VALUES (
       UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), 'Vehicle', UUID_TO_BIN(UUID()),
       'vehicle.created', 'integration.events', '{"vin":"MANUAL-VIN-001"}',
       'PENDING', 0, NOW(6), NOW(6)
   );
   ```
4. **Paso 2 (Esperar ciclo de polling del scheduler):**
   El scheduler ejecuta `pollAndRelay()` cada 1000 ms.
5. **Resultado Esperado:**
   - La fila en `integration_outbox` pasa a `status = 'PUBLISHED'` con `published_at IS NOT NULL`.
   - El mensaje `{"vin":"MANUAL-VIN-001"}` aparece en el topic Kafka `integration.events`.

### OIB-MAN-02: Deduplicación Idempotente en Inbox

1. **Objetivo:** Verificar que la llegada de un mismo `eventId` múltiples veces solo ejecute la acción de negocio una vez.
2. **Precondición:** Evento disponible en `integration.events`.
3. **Paso 1:** Enviar mensaje con cabecera `id: e8a53a1a-cdb0-4854-b966-fed811b3bc21` y `tenantId: ba168da1-0539-4b92-81ad-2d98dfffd1d1`.
4. **Paso 2:** Reenviar el mismo mensaje inmediatamente (simulación de reintento Kafka o redelivery).
5. **Resultado Esperado:**
   - Primer mensaje: Registrado en `integration_inbox` con `status = 'PROCESSED'`, consumidor de negocio ejecutado.
   - Segundo mensaje: Detectado como duplicado (`recordIfAbsent` retorna `false`), consumidor no se vuelve a invocar.

### OIB-MAN-03: Manejo de Falla y Reenvío a DLQ

1. **Objetivo:** Validar que cargas malformadas o errores de negocio no bloqueen el procesamiento y se canalicen a la Dead Letter Queue.
2. **Paso 1:** Enviar mensaje con payload inválido que provoque excepción en el consumidor.
3. **Resultado Esperado:**
   - Registro en `integration_inbox_dlq` con `error_message` y stack trace.
   - Evento publicado en el topic DLQ `integration.events.dlq`.
   - Offset del consumer commitado para continuar consumiendo siguientes mensajes sin bloqueo.

---

## 5. Evidencia de Ejecución Automatizada

Ejecución de la suite completa con Maven (`mvn clean test`):

```text
[INFO] Scanning for projects...
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Build Order:
[INFO]   integration-parent                                              [pom]
[INFO]   integration                                                     [jar]
[INFO]   integration-e2e                                                 [jar]
[INFO] 
[INFO] -------------------< com.cl2:integration-parent >--------------------
[INFO] Building integration-parent 0.0.1-SNAPSHOT                         [1/3]
[INFO] --------------------------------[ pom ]---------------------------------
[INFO] 
[INFO] ------------------------< com.cl2:integration >-------------------------
[INFO] Building integration 0.0.1-SNAPSHOT                                [2/3]
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] Running com.cl2.integration.adapter.in.web.IntegrationProfileControllerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.adapter.out.persistence.IntegrationProfilePersistenceAdapterTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.infrastructure.tenant.TenantFilterTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.integration.inbox.InboxEntityTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.integration.inbox.InboxProcessorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.integration.outbox.OutboxEntityTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.integration.outbox.OutboxRelaySchedulerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.integration.profile.IntegrationProfileEventPublisherTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.integration.OutboxInboxFlowIntegrationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.IntegrationProfileEndToEndTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] Tests run: 84, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ----------------------< com.cl2:integration-e2e >-----------------------
[INFO] Building integration-e2e 0.0.1-SNAPSHOT                            [3/3]
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] Running com.cl2.integration.e2e.E2eApplicationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.e2e.IntegrationProfileE2ETest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.cl2.integration.e2e.KafkaEventObserverTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for integration-parent 0.0.1-SNAPSHOT:
[INFO] 
[INFO] integration-parent ................................. SUCCESS [  0.219 s]
[INFO] integration ........................................ SUCCESS [ 49.522 s]
[INFO] integration-e2e .................................... SUCCESS [ 26.657 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total tests executed: 90
[INFO] Failures: 0, Errors: 0, Skipped: 0 (100% PASS)
```
