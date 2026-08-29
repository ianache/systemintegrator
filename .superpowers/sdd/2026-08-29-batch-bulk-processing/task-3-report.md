# Task 3 Report: Propagate batch headers through Kafka and the inbox

## Changed files

- `application/src/main/java/com/cl2/integration/integration/outbox/KafkaOutboxPublisher.java`
  - Injects Jackson `ObjectMapper` for the Spring-managed publisher while preserving the existing one-argument constructor for direct callers.
  - Preserves existing Kafka headers and adds UTF-8 `X-Batch-Mode: true` and `X-Batch-Size` headers for event types ending in `.batch.upserted`.
  - Validates that batch payloads are JSON arrays and fails before Kafka send for invalid or non-array payloads.
- `application/src/main/java/com/cl2/integration/integration/inbox/KafkaInboxListener.java`
  - Reads batch headers and forwards a parsed `BatchContext` with the existing event, tenant, payload, and external-source values.
  - Defaults missing, malformed, non-positive, or non-`true` headers to `BatchContext.unitary()`.
- `application/src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java`
  - Adds the six-argument `dispatch` compatibility overload and delegates to the existing five-argument implementation unchanged.
- `application/src/test/java/com/cl2/integration/integration/outbox/KafkaOutboxPublisherTest.java`
  - Covers batch headers and their JSON array count, absence of batch headers on unitary events, and invalid batch payload rejection before send.
- `application/src/test/java/com/cl2/integration/integration/inbox/KafkaInboxListenerTest.java`
  - Covers valid case-insensitive batch headers, missing headers, and invalid batch-size headers, with Mockito verification of the six-argument dispatcher call.

## TDD evidence

### Publisher red

Command:

```powershell
mvn -pl application "-Dtest=KafkaOutboxPublisherTest" test
```

Output: exit code `1`; 4 tests ran with 2 failures. The batch event lacked `X-Batch-Mode`, and a non-array batch payload did not throw.

### Publisher green

Command:

```powershell
mvn -pl application "-Dtest=KafkaOutboxPublisherTest" test
```

Output: exit code `0`; `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

### Listener red

Command:

```powershell
mvn -pl application "-Dtest=KafkaOutboxPublisherTest,KafkaInboxListenerTest" test
```

Output: exit code `1`; test compilation failed because `OutboundEventDispatcher` did not yet provide the required six-argument compatibility overload.

### Listener green / required focused verification

Command:

```powershell
mvn -pl application "-Dtest=KafkaOutboxPublisherTest,KafkaInboxListenerTest" test
```

Output: exit code `0`; `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`.

## Self-review

- The diff contains only the five implementation/test files listed in the task brief plus this required report.
- Existing Kafka provenance and business-domain headers remain present.
- Existing inbox extraction, idempotency callback, metric recording, and dispatcher behavior remain unchanged apart from passing the additive context argument.
- `git diff --check` passed; Git reported only the repository's LF/CRLF normalization warnings.

## Commit

Pending final verification and commit.

## Concerns

The focused Maven runs emit pre-existing compiler deprecation warnings and Mockito/JDK dynamic-agent warnings. They do not produce test failures or errors for this task.
