# Final fix wave report

Date: 2026-08-29

## Status

All five final-review findings and the documentation hierarchy issue are implemented. Focused regression and integration verification passed. The two requested full Maven suites were not rerun after the fixes because the user directed that long-running verification be stopped; the application-suite baseline remains blocked by unavailable Docker/Testcontainers.

## Findings resolved

1. **Batch retries preserve context.** Added `BatchContextResolver` and used it in both dead-letter replay and monitor retry paths. A persisted `.batch.upserted` event with a non-empty JSON array now reconstructs batch mode and size before dispatch.
2. **Batch transformation output is publishable.** The sync orchestrator now requires a non-empty JSON array from batch transformation before saving outbox state. Invalid output fails the sync before outbox or watermark persistence, while valid real JSLT array transformations remain supported.
3. **Watermark overlap does not republish delivered rows.** Added stable per-row delivery identities derived from tenant, domain, and business key, plus normalized persisted delivery-key lookup. Previously delivered rows are filtered before output batches are rebuilt, while the complete extraction still determines the transactional watermark.
4. **`externalSource` reaches Kafka.** Added nullable outbox persistence for the source and conditional `X-External-Source` publication. Existing outbox rows without a source remain compatible and omit the header.
5. **Batch bypass is strict.** Transformation bypass now requires both a `.batch.upserted` event type and valid batch context. The dispatcher rejects empty/non-array payloads and payload counts that differ from `X-Batch-Size`; a unit event cannot bypass transformation merely by carrying batch headers.
6. **Documentation hierarchy is corrected.** Operational bullets remain under section 6 and batch behavior is section 7.

## Design decisions

- Batch retry context is reconstructed from durable event type and payload rather than adding transient retry-only state.
- Delivery identities are stored in a child table instead of embedding mutable metadata in the outbox payload. This keeps one outbox event per output batch and supports repository-level delivered-key checks.
- The source column and Kafka header are nullable/conditional for backward compatibility.
- Batch output validation occurs before outbox save so malformed events cannot enter the publisher retry loop.

## Changed files

### Production

- `application/src/main/java/com/cl2/integration/integration/batch/BatchContextResolver.java`
- `application/src/main/java/com/cl2/integration/integration/inbox/DeadLetterQueueReplayService.java`
- `application/src/main/java/com/cl2/integration/integration/monitor/MessageMonitorService.java`
- `application/src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java`
- `application/src/main/java/com/cl2/integration/integration/outbox/KafkaOutboxPublisher.java`
- `application/src/main/java/com/cl2/integration/integration/outbox/OutboxDeliveryKeyJpaEntity.java`
- `application/src/main/java/com/cl2/integration/integration/outbox/OutboxEvent.java`
- `application/src/main/java/com/cl2/integration/integration/outbox/OutboxJpaEntity.java`
- `application/src/main/java/com/cl2/integration/integration/outbox/OutboxPersistenceAdapter.java`
- `application/src/main/java/com/cl2/integration/integration/outbox/OutboxRepository.java`
- `application/src/main/java/com/cl2/integration/integration/outbox/SpringDataOutboxDeliveryKeyRepository.java`
- `application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java`
- `application/src/main/resources/db/migration/V9__add_outbox_delivery_keys.sql`
- `application/src/main/resources/db/migration/V10__add_outbox_external_source.sql`

### Tests

- `application/src/test/java/com/cl2/integration/integration/inbox/DeadLetterQueueReplayServiceTest.java`
- `application/src/test/java/com/cl2/integration/integration/monitor/MessageMonitorServiceTest.java`
- `application/src/test/java/com/cl2/integration/integration/outbound/OutboundEventDispatcherTest.java`
- `application/src/test/java/com/cl2/integration/integration/outbox/KafkaOutboxPublisherTest.java`
- `application/src/test/java/com/cl2/integration/integration/outbox/SpringDataOutboxRepositoryTest.java`
- `application/src/test/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestratorTest.java`

### Documentation

- `docs/api-rest-adapter.md`
- `docs/superpowers/specs/2026-08-29-batch-bulk-processing-design.md`
- `.superpowers/sdd/2026-08-29-batch-bulk-processing/final-fix-report.md`

## TDD evidence

### Red

- `mvn -pl application "-Dtest=OutboundEventDispatcherTest,DeadLetterQueueReplayServiceTest,MessageMonitorServiceTest" test` — exit 1; 27 tests, 5 expected failures covering lost retry context and invalid batch bypass.
- `mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test` — exit 1; 21 tests, 2 expected failures covering overlap republishing and invalid object output. The valid real JSLT array case already passed in this run.
- `mvn -pl application -Dtest=KafkaOutboxPublisherTest test` — exit 1 during test compilation because the new source-aware outbox factory did not yet exist.

### Green focused verification

- `mvn -pl application "-Dtest=OutboundEventDispatcherTest,DeadLetterQueueReplayServiceTest,MessageMonitorServiceTest" test` — exit 0; 27 tests, 0 failures, 0 errors.
- `mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test` — exit 0; 21 tests, 0 failures, 0 errors.
- `mvn -pl application -Dtest=KafkaOutboxPublisherTest test` — exit 0; 5 tests, 0 failures, 0 errors.
- `mvn -pl application "-Dtest=IntegrationSyncOrchestratorTest,KafkaOutboxPublisherTest,SpringDataOutboxRepositoryTest,OutboxEntityTest,OutboundEventDispatcherTest,DeadLetterQueueReplayServiceTest,MessageMonitorServiceTest,KafkaInboxListenerTest" test` — exit 0; 59 tests, 0 failures, 0 errors. This also exercised Flyway migrations V9/V10 and persistence of delivery identities and `externalSource` against the configured test database.
- `mvn -pl application -Dtest=OutboundEventDispatchIntegrationTest test` — exit 0; 6 tests, 0 failures, 0 errors.

### Requested full verification

- Pre-fix baseline `mvn -pl application test` — exit 1; 318 tests, 0 failures, 1 error. `IntegrationSyncEndToEndTest` could not find a valid Docker environment through Testcontainers.
- Post-fix `mvn -pl application test` — not rerun; stopped/deferred per explicit user instruction to end long-running verification. It remains unverified as a full suite.
- Post-fix `mvn test` — not run; stopped/deferred per explicit user instruction. It remains unverified as a reactor-wide suite.
- `git diff --check` — passed after removing three trailing spaces in test fixtures.

## Residual concerns

- The complete application and reactor-wide Maven suites remain unverified after the fix wave. Docker/Testcontainers was unavailable in the clean baseline, so the end-to-end Testcontainers test is still environmentally blocked.
- Delivery-key metadata is generated for newly persisted batches. Historical outbox rows created before migration V9 have no row-level delivery keys and cannot be safely backfilled from arbitrary transformed payloads; such legacy data does not receive overlap deduplication retroactively.
