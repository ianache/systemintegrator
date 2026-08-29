package com.cl2.integration.integration.sync;

import com.cl2.integration.adapter.out.generic.GenericRestAdapter;
import com.cl2.integration.adapter.out.generic.GenericJdbcAdapter;
import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxRepository;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.transformation.TransformationService;
import com.cl2.integration.integration.transformation.PassthroughPayloadTransformer;
import com.cl2.integration.integration.transformation.jslt.JsltPayloadTransformer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationSyncOrchestratorTest {

    private SecretResolver secretResolver;
    private JdbcDataSourceFactory jdbcDataSourceFactory;
    private GenericJdbcAdapter genericJdbcAdapter;
    private GenericRestAdapter genericRestAdapter;
    private TransformationService transformationService;
    private ResilienceExecutor resilienceExecutor;
    private OutboxRepository outboxRepository;
    private SyncStateRepository syncStateRepository;
    private SyncStateRecorder syncStateRecorder;
    private com.cl2.integration.infrastructure.metrics.IntegrationMetrics metrics;
    private IntegrationSyncOrchestrator orchestrator;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        jdbcDataSourceFactory = mock(JdbcDataSourceFactory.class);
        genericJdbcAdapter = mock(GenericJdbcAdapter.class);
        genericRestAdapter = mock(GenericRestAdapter.class);
        transformationService = mock(TransformationService.class);
        resilienceExecutor = mock(ResilienceExecutor.class);
        outboxRepository = mock(OutboxRepository.class);
        syncStateRepository = mock(SyncStateRepository.class);
        syncStateRecorder = mock(SyncStateRecorder.class);
        metrics = mock(com.cl2.integration.infrastructure.metrics.IntegrationMetrics.class);

        orchestrator = instantiateOrchestrator();

        // ResilienceExecutor just runs the supplier synchronously in these tests
        when(resilienceExecutor.execute(any(), anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(2);
            return supplier.get();
        });
    }

    private IntegrationProfile profileWith(String extractionConfigJson, String syncPolicyJson) {
        return profileWith(IntegrationProtocol.JDBC, "jdbc:mysql://localhost:3306/integration", extractionConfigJson, syncPolicyJson);
    }

    private IntegrationProfile profileWith(
            IntegrationProtocol protocol,
            String endpoint,
            String extractionConfigJson,
            String syncPolicyJson
    ) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                protocol, "generic-jdbc", "generic-jdbc-adapter",
                endpoint, "secret/sap/hana",
                "{\"customerId\":\"CardCode\"}", null, syncPolicyJson, null, null, extractionConfigJson);
        return IntegrationProfile.rehydrate(profileId, tenantId, "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config, true, Instant.now(), Instant.now(), 0);
    }

    private IntegrationSyncOrchestrator instantiateOrchestrator() {
        try {
            Constructor<IntegrationSyncOrchestrator> constructor = IntegrationSyncOrchestrator.class.getConstructor(
                    SecretResolver.class,
                    JdbcDataSourceFactory.class,
                    GenericJdbcAdapter.class,
                    GenericRestAdapter.class,
                    TransformationService.class,
                    ResilienceExecutor.class,
                    OutboxRepository.class,
                    SyncStateRepository.class,
                    SyncStateRecorder.class,
                    ObjectMapper.class,
                    com.cl2.integration.infrastructure.metrics.IntegrationMetrics.class
            );
            return constructor.newInstance(
                    secretResolver,
                    jdbcDataSourceFactory,
                    genericJdbcAdapter,
                    genericRestAdapter,
                    transformationService,
                    resilienceExecutor,
                    outboxRepository,
                    syncStateRepository,
                    syncStateRecorder,
                    new ObjectMapper(),
                    metrics
            );
        } catch (NoSuchMethodException ignored) {
            return new IntegrationSyncOrchestrator(
                    secretResolver,
                    jdbcDataSourceFactory,
                    genericJdbcAdapter,
                    transformationService,
                    resilienceExecutor,
                    outboxRepository,
                    syncStateRepository,
                    syncStateRecorder,
                    new ObjectMapper(),
                    metrics
            );
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to instantiate IntegrationSyncOrchestrator for test", ex);
        }
    }

    @Test
    void extractsTransformsAndPublishesRowsThenAdvancesTheWatermarkInOneRun() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode, updated_at FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"CardCode\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\",\"overlapBufferSeconds\":300}");

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(eq("jdbc:mysql://localhost:3306/integration"), eq(secret))).thenReturn(dataSource);

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(Map.of("CardCode", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp)));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

        orchestrator.run(profile);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(outboxCaptor.getValue().aggregateType()).isEqualTo("customers");
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("customers.upserted");
        assertThat(outboxCaptor.getValue().payload()).isEqualTo("{\"customerId\":\"CLI-001\"}");

        ArgumentCaptor<SyncState> stateCaptor = ArgumentCaptor.forClass(SyncState.class);
        verify(syncStateRepository).upsert(stateCaptor.capture());
        assertThat(stateCaptor.getValue().profileId()).isEqualTo(profileId);
        assertThat(stateCaptor.getValue().lastRunStatus()).isEqualTo(SyncRunStatus.SUCCESS);
        assertThat(stateCaptor.getValue().lastWatermark()).isEqualTo(rowTimestamp.minusSeconds(300));

        verify(syncStateRecorder, never()).recordFailure(any(), any(), anyString());
        verify(metrics).recordOutboxEventSaved(eq(tenantId.toString()), eq("customers"), eq("customers.upserted"));
        verify(metrics).recordSyncRun(eq(tenantId.toString()), eq("customers"), eq("sap-hana"), eq("SUCCESS"), any(Double.class), eq(1));
    }

    @Test
    void batchesFiveJdbcRowsIntoContiguousJsonArraysAndPublishesOneEventPerBatch() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode, updated_at FROM customers\","
                + "\"keyColumn\":\"CardCode\",\"watermarkColumn\":\"updated_at\",\"batchMode\":true,\"batchSize\":2}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"overlapBufferSeconds\":60}");
        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        Instant firstTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(
                Map.of("CardCode", "CLI-001", "updated_at", java.sql.Timestamp.from(firstTimestamp)),
                Map.of("CardCode", "CLI-002", "updated_at", java.sql.Timestamp.from(firstTimestamp.plusSeconds(1))),
                Map.of("CardCode", "CLI-003", "updated_at", java.sql.Timestamp.from(firstTimestamp.plusSeconds(2))),
                Map.of("CardCode", "CLI-004", "updated_at", java.sql.Timestamp.from(firstTimestamp.plusSeconds(3))),
                Map.of("CardCode", "CLI-005", "updated_at", java.sql.Timestamp.from(firstTimestamp.plusSeconds(4)))
        );
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient()));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH))).thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile)))
                .thenReturn("[{\"batch\":1}]", "[{\"batch\":2}]", "[{\"batch\":3}]");

        orchestrator.run(profile);

        ArgumentCaptor<String> transformationCaptor = ArgumentCaptor.forClass(String.class);
        verify(transformationService, times(3)).transform(transformationCaptor.capture(), eq(profile));
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(0))).hasSize(2);
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(1))).hasSize(2);
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(2))).hasSize(1);
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(0)).get(0).get("CardCode").asText()).isEqualTo("CLI-001");
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(1)).get(0).get("CardCode").asText()).isEqualTo("CLI-003");
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(2)).get(0).get("CardCode").asText()).isEqualTo("CLI-005");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(3)).save(outboxCaptor.capture(), anyList());
        assertThat(outboxCaptor.getAllValues()).allSatisfy(event -> {
            assertThat(event.aggregateType()).isEqualTo("customers");
            assertThat(event.eventType()).isEqualTo("customers.batch.upserted");
            assertThat(event.topic()).isEqualTo("integration.customers.batch.events");
        });
        assertThat(outboxCaptor.getAllValues()).extracting(OutboxEvent::payload)
                .containsExactly("[{\"batch\":1}]", "[{\"batch\":2}]", "[{\"batch\":3}]");

        ArgumentCaptor<SyncState> stateCaptor = ArgumentCaptor.forClass(SyncState.class);
        verify(syncStateRepository).upsert(stateCaptor.capture());
        assertThat(stateCaptor.getValue().lastWatermark()).isEqualTo(firstTimestamp.plusSeconds(4).minusSeconds(60));
    }

    @Test
    void batchesFiveRestRowsUsingTheRestBusinessKey() throws Exception {
        String extractionConfigJson = "{\"method\":\"GET\",\"path\":\"/customers\",\"responseJsonPath\":\"$.items[*]\","
                + "\"keyProperty\":\"externalId\",\"watermarkColumn\":\"updated_at\",\"batchMode\":true,\"batchSize\":2}";
        IntegrationProfile profile = profileWith(IntegrationProtocol.REST, "https://api.example.com", extractionConfigJson, "{}");
        ResolvedSecret secret = ResolvedSecret.bearer("secret/sap/hana", "token-123");
        Instant firstTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(
                Map.of("externalId", "REST-001", "updated_at", firstTimestamp.toString()),
                Map.of("externalId", "REST-002", "updated_at", firstTimestamp.plusSeconds(1).toString()),
                Map.of("externalId", "REST-003", "updated_at", firstTimestamp.plusSeconds(2).toString()),
                Map.of("externalId", "REST-004", "updated_at", firstTimestamp.plusSeconds(3).toString()),
                Map.of("externalId", "REST-005", "updated_at", firstTimestamp.plusSeconds(4).toString())
        );
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(genericRestAdapter.extract(any(IntegrationProfile.class), any(ExtractionConfig.class), eq(secret), eq(Instant.EPOCH))).thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile)))
                .thenReturn("[{\"batch\":1}]", "[{\"batch\":2}]", "[{\"batch\":3}]");

        orchestrator.run(profile);

        ArgumentCaptor<String> transformationCaptor = ArgumentCaptor.forClass(String.class);
        verify(transformationService, times(3)).transform(transformationCaptor.capture(), eq(profile));
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(0))).hasSize(2);
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(1))).hasSize(2);
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(2))).hasSize(1);
        assertThat(mapper.readTree(transformationCaptor.getAllValues().get(0)).get(0).get("externalId").asText()).isEqualTo("REST-001");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(3)).save(outboxCaptor.capture(), anyList());
        assertThat(outboxCaptor.getAllValues()).allSatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("customers.batch.upserted");
            assertThat(event.topic()).isEqualTo("integration.customers.batch.events");
        });
    }

    @Test
    void doesNotTransformOrPublishWhenBatchExtractionIsEmpty() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode FROM customers\",\"keyColumn\":\"CardCode\","
                + "\"watermarkColumn\":\"updated_at\",\"batchMode\":true,\"batchSize\":2}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{}");
        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient()));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH))).thenReturn(List.of());

        orchestrator.run(profile);

        verify(transformationService, never()).transform(anyString(), any());
        verify(outboxRepository, never()).save(any());
        ArgumentCaptor<SyncState> stateCaptor = ArgumentCaptor.forClass(SyncState.class);
        verify(syncStateRepository).upsert(stateCaptor.capture());
        assertThat(stateCaptor.getValue().lastWatermark()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void publishesOneBatchWhenTheExtractedRowsAreSmallerThanBatchSize() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode FROM customers\",\"keyColumn\":\"CardCode\","
                + "\"watermarkColumn\":\"updated_at\",\"batchMode\":true,\"batchSize\":2}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{}");
        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient()));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of("CardCode", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp))));
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("[{\"batch\":true}]");

        orchestrator.run(profile);

        ArgumentCaptor<String> transformationCaptor = ArgumentCaptor.forClass(String.class);
        verify(transformationService).transform(transformationCaptor.capture(), eq(profile));
        assertThat(new ObjectMapper().readTree(transformationCaptor.getValue())).hasSize(1);
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture(), anyList());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("customers.batch.upserted");
    }

    @Test
    void skipsEveryBatchOnAnIdenticalDeterministicRerun() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode FROM customers\",\"keyColumn\":\"CardCode\","
                + "\"watermarkColumn\":\"updated_at\",\"batchMode\":true,\"batchSize\":2}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{}");
        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        Instant firstTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(
                Map.of("CardCode", "CLI-001", "updated_at", java.sql.Timestamp.from(firstTimestamp)),
                Map.of("CardCode", "CLI-002", "updated_at", java.sql.Timestamp.from(firstTimestamp.plusSeconds(1))),
                Map.of("CardCode", "CLI-003", "updated_at", java.sql.Timestamp.from(firstTimestamp.plusSeconds(2))),
                Map.of("CardCode", "CLI-004", "updated_at", java.sql.Timestamp.from(firstTimestamp.plusSeconds(3))),
                Map.of("CardCode", "CLI-005", "updated_at", java.sql.Timestamp.from(firstTimestamp.plusSeconds(4)))
        );
        Map<UUID, OutboxEvent> savedEvents = new HashMap<>();
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient()));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH))).thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("[{\"batch\":true}]");
        when(outboxRepository.findLatestByAggregateId(eq(tenantId), any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(savedEvents.get(invocation.getArgument(1))));
        when(outboxRepository.save(any(OutboxEvent.class), anyList())).thenAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            savedEvents.put(event.aggregateId(), event);
            return event;
        });

        orchestrator.run(profile);
        orchestrator.run(profile);

        verify(outboxRepository, times(3)).save(any(OutboxEvent.class), anyList());
        ArgumentCaptor<UUID> aggregateIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(outboxRepository, times(6)).findLatestByAggregateId(eq(tenantId), aggregateIdCaptor.capture());
        List<UUID> aggregateIds = aggregateIdCaptor.getAllValues();
        assertThat(aggregateIds.subList(0, 3)).containsExactlyElementsOf(aggregateIds.subList(3, 6));
        assertThat(new ArrayList<>(savedEvents.keySet())).hasSize(3);
    }

    @Test
    void rejectsObjectTransformationOutputBeforeSavingABatchEvent() {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode FROM customers\",\"keyColumn\":\"CardCode\","
                + "\"watermarkColumn\":\"updated_at\",\"batchMode\":true,\"batchSize\":2}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{}");
        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret)))
                .thenReturn(mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient()));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of(
                        "CardCode", "CLI-001",
                        "updated_at", java.sql.Timestamp.from(rowTimestamp))));
        when(transformationService.transform(anyString(), eq(profile)))
                .thenReturn("{\"customerId\":\"CLI-001\"}");

        assertThatThrownBy(() -> orchestrator.run(profile))
                .isInstanceOf(IntegrationSyncException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Batch transformation output must be a non-empty JSON array");

        verify(outboxRepository, never()).save(any());
        verify(outboxRepository, never()).save(any(), anyList());
        verify(syncStateRepository, never()).upsert(any());
    }

    @Test
    void preservesAValidJsltArrayTransformationForBatchPersistence() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode FROM customers\",\"keyColumn\":\"CardCode\","
                + "\"watermarkColumn\":\"updated_at\",\"batchMode\":true,\"batchSize\":2}";
        String transformation = "{\"engine\":\"JSLT\",\"script\":\"[for (.) {\\\"customerId\\\": .CardCode}]\"}";
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC,
                "generic-jdbc",
                "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration",
                "secret/sap/hana",
                null,
                transformation,
                "{}",
                null,
                null,
                extractionConfigJson);
        IntegrationProfile profile = IntegrationProfile.rehydrate(
                profileId,
                tenantId,
                "customers",
                "sap-hana",
                SyncDirection.INBOUND,
                SourceOfTruth.EXTERNAL,
                config,
                true,
                Instant.now(),
                Instant.now(),
                0);
        ObjectMapper mapper = new ObjectMapper();
        transformationService = new TransformationService(
                List.of(new PassthroughPayloadTransformer(), new JsltPayloadTransformer(mapper)),
                mapper);
        orchestrator = instantiateOrchestrator();

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret)))
                .thenReturn(mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient()));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of(
                        "CardCode", "CLI-001",
                        "updated_at", java.sql.Timestamp.from(rowTimestamp))));

        orchestrator.run(profile);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(eventCaptor.capture(), anyList());
        assertThat(mapper.readTree(eventCaptor.getValue().payload()))
                .isEqualTo(mapper.readTree("[{\"customerId\":\"CLI-001\"}]"));
    }

    @Test
    void overlappingBatchExtractionsPublishOnlyPreviouslyUndeliveredBusinessKeys() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode FROM customers\",\"keyColumn\":\"CardCode\","
                + "\"watermarkColumn\":\"updated_at\",\"batchMode\":true,\"batchSize\":2}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"overlapBufferSeconds\":300}");
        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        Instant timestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> firstRows = List.of(
                row("A", timestamp),
                row("B", timestamp.plusSeconds(1)),
                row("C", timestamp.plusSeconds(2)));
        List<Map<String, Object>> overlappingRows = List.of(
                row("B", timestamp.plusSeconds(1)),
                row("C", timestamp.plusSeconds(2)),
                row("D", timestamp.plusSeconds(3)));
        Map<UUID, OutboxEvent> savedEvents = new HashMap<>();
        java.util.Set<UUID> deliveredIds = new java.util.HashSet<>();
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret)))
                .thenReturn(mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient()));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(firstRows, overlappingRows);
        when(transformationService.transform(anyString(), eq(profile)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxRepository.findLatestByAggregateId(eq(tenantId), any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(savedEvents.get(invocation.getArgument(1))));
        when(outboxRepository.findExistingDeliveryIds(eq(tenantId), any()))
                .thenAnswer(invocation -> {
                    java.util.Collection<UUID> requested = invocation.getArgument(1);
                    return requested.stream().filter(deliveredIds::contains).collect(java.util.stream.Collectors.toSet());
                });
        when(outboxRepository.save(any(OutboxEvent.class), anyList())).thenAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            List<UUID> deliveryIds = invocation.getArgument(1);
            savedEvents.put(event.aggregateId(), event);
            deliveredIds.addAll(deliveryIds);
            return event;
        });

        orchestrator.run(profile);
        orchestrator.run(profile);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(3)).save(eventCaptor.capture(), anyList());
        assertThat(eventCaptor.getAllValues().stream()
                .map(OutboxEvent::payload)
                .map(this::batchBusinessKeys)
                .toList())
                .containsExactly(List.of("A", "B"), List.of("C"), List.of("D"));
    }

    private Map<String, Object> row(String businessKey, Instant timestamp) {
        return Map.of(
                "CardCode", businessKey,
                "updated_at", java.sql.Timestamp.from(timestamp));
    }

    private List<String> batchBusinessKeys(String payload) {
        try {
            List<String> keys = new ArrayList<>();
            new ObjectMapper().readTree(payload).forEach(node -> keys.add(node.get("CardCode").asText()));
            return keys;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid captured batch payload", exception);
        }
    }

    @Test
    void derivesVehicleAggregateAndVehicleUpsertedForUnitsDomain() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT motor, updated_at FROM vehicles WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"motor\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "sigo", "sigo-adapter",
                "jdbc:mysql://localhost:3306/integration", "secret/sigo",
                "{\"motor\":\"numero_motor\"}", null, "{\"cronExpression\":\"0 */10 * * * *\"}", null, null, extractionConfigJson);
        IntegrationProfile profile = IntegrationProfile.rehydrate(profileId, tenantId, "units", "sigo",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config, true, Instant.now(), Instant.now(), 0);

        ResolvedSecret secret = ResolvedSecret.basic("secret/sigo", "user", "pass");
        when(secretResolver.resolve("secret/sigo", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(eq("jdbc:mysql://localhost:3306/integration"), eq(secret))).thenReturn(dataSource);

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(Map.of("motor", "MOT-123", "updated_at", java.sql.Timestamp.from(rowTimestamp)));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"motor\":\"MOT-123\"}");

        orchestrator.run(profile);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().aggregateType()).isEqualTo("units");
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("units.upserted");
        assertThat(outboxCaptor.getValue().payload()).isEqualTo("{\"motor\":\"MOT-123\"}");
        assertThat(outboxCaptor.getValue().topic()).isEqualTo("integration.units.events");
    }

    @Test
    void derivesDomainSpecificTopicForOutboxEvent() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT card_code, updated_at FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"card_code\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\"}");

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(dataSource);

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(Map.of("card_code", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp)));
        when(genericJdbcAdapter.extract(any(), any(), eq(Instant.EPOCH))).thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

        orchestrator.run(profile);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().topic()).isEqualTo("integration.customers.events");
    }

    @Test
    void restProfilesDelegateToGenericRestAdapterAndSkipJdbcExtraction() throws Exception {
        String extractionConfigJson = "{\"method\":\"GET\",\"path\":\"/customers\",\"responseJsonPath\":\"$.items[*]\","
                + "\"keyProperty\":\"externalId\",\"keyColumn\":\"legacyId\",\"watermarkParam\":\"updatedSince\",\"watermarkFormat\":\"ISO_8601\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(
                IntegrationProtocol.REST,
                "https://api.example.com",
                extractionConfigJson,
                "{\"cronExpression\":\"0 */10 * * * *\",\"overlapBufferSeconds\":60}");

        ResolvedSecret secret = ResolvedSecret.bearer("secret/sap/hana", "token-123");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> restRows = List.of(Map.of(
                "externalId", "CLI-REST-001",
                "legacyId", "CLI-001",
                "updated_at", java.sql.Timestamp.from(rowTimestamp)
        ));
        when(genericRestAdapter.extract(any(IntegrationProfile.class), any(ExtractionConfig.class), eq(secret), eq(Instant.EPOCH)))
                .thenReturn(restRows);
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-REST-001\"}");

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(dataSource);
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of("legacyId", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp))));

        orchestrator.run(profile);

        verify(genericRestAdapter).extract(any(IntegrationProfile.class), any(ExtractionConfig.class), eq(secret), eq(Instant.EPOCH));
        verify(genericJdbcAdapter, never()).extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), any());
        verify(jdbcDataSourceFactory, never()).create(anyString(), any());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().payload()).isEqualTo("{\"customerId\":\"CLI-REST-001\"}");

        ArgumentCaptor<SyncState> stateCaptor = ArgumentCaptor.forClass(SyncState.class);
        verify(syncStateRepository).upsert(stateCaptor.capture());
        assertThat(stateCaptor.getValue().lastWatermark()).isEqualTo(rowTimestamp.minusSeconds(60));
    }

    @Test
    void restProfilesAcceptIso8601StringWatermarksFromTheAdapter() throws Exception {
        String extractionConfigJson = "{\"method\":\"GET\",\"path\":\"/customers\",\"responseJsonPath\":\"$.items[*]\","
                + "\"keyProperty\":\"externalId\",\"watermarkParam\":\"updatedSince\",\"watermarkFormat\":\"ISO_8601\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(
                IntegrationProtocol.REST,
                "https://api.example.com",
                extractionConfigJson,
                "{\"cronExpression\":\"0 */10 * * * *\",\"overlapBufferSeconds\":60}");

        ResolvedSecret secret = ResolvedSecret.bearer("secret/sap/hana", "token-123");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        when(genericRestAdapter.extract(any(IntegrationProfile.class), any(ExtractionConfig.class), eq(secret), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of(
                        "externalId", "CLI-REST-002",
                        "updated_at", rowTimestamp.toString()
                )));
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-REST-002\"}");

        orchestrator.run(profile);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().payload()).isEqualTo("{\"customerId\":\"CLI-REST-002\"}");

        ArgumentCaptor<SyncState> stateCaptor = ArgumentCaptor.forClass(SyncState.class);
        verify(syncStateRepository).upsert(stateCaptor.capture());
        assertThat(stateCaptor.getValue().lastWatermark()).isEqualTo(rowTimestamp.minusSeconds(60));
    }

    @Test
    void jdbcProfilesContinueToDelegateToGenericJdbcAdapter() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode, updated_at FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"CardCode\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\"}");

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(eq("jdbc:mysql://localhost:3306/integration"), eq(secret))).thenReturn(dataSource);

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(Map.of("CardCode", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp)));
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

        orchestrator.run(profile);

        verify(genericJdbcAdapter).extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH));
        verify(genericRestAdapter, never()).extract(any(), any(), any(), any());
    }

    @Test
    void unsupportedProtocolFailsExplicitlyBeforeWritingEvents() {
        String extractionConfigJson = "{\"path\":\"/customers\",\"responseJsonPath\":\"$.items[*]\","
                + "\"keyProperty\":\"externalId\",\"keyColumn\":\"legacyId\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(
                IntegrationProtocol.SOAP,
                "https://api.example.com",
                extractionConfigJson,
                "{\"cronExpression\":\"0 */10 * * * *\"}");

        ResolvedSecret secret = ResolvedSecret.bearer("secret/sap/hana", "token-123");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(dataSource);
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of("legacyId", "CLI-001", "updated_at", java.sql.Timestamp.from(Instant.parse("2026-08-01T10:00:00Z")))));
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

        assertThatThrownBy(() -> orchestrator.run(profile))
                .isInstanceOf(IntegrationSyncException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sync failed for profile " + profileId);

        verify(outboxRepository, never()).save(any());
        verify(syncStateRepository, never()).upsert(any());
    }

    @Test
    void missingRestKeyFailsBeforeOutboxSave() {
        String extractionConfigJson = "{\"method\":\"GET\",\"path\":\"/customers\",\"responseJsonPath\":\"$.items[*]\","
                + "\"keyProperty\":\"externalId\",\"keyColumn\":\"legacyId\",\"watermarkParam\":\"updatedSince\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(
                IntegrationProtocol.REST,
                "https://api.example.com",
                extractionConfigJson,
                "{\"cronExpression\":\"0 */10 * * * *\"}");

        ResolvedSecret secret = ResolvedSecret.bearer("secret/sap/hana", "token-123");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        when(genericRestAdapter.extract(any(IntegrationProfile.class), any(ExtractionConfig.class), eq(secret), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of("legacyId", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp))));
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(dataSource);
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of("legacyId", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp))));

        assertThatThrownBy(() -> orchestrator.run(profile))
                .isInstanceOf(IntegrationSyncException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sync failed for profile " + profileId);

        verify(outboxRepository, never()).save(any());
        verify(syncStateRepository, never()).upsert(any());
        verify(syncStateRecorder).recordFailure(eq(profileId), any(Instant.class), anyString());
    }

    @Test
    void restAdapterFailureRecordsFailureWithoutPersistingSuccessfulWatermark() {
        String extractionConfigJson = "{\"method\":\"GET\",\"path\":\"/customers\",\"responseJsonPath\":\"$.items[*]\","
                + "\"keyProperty\":\"externalId\",\"keyColumn\":\"legacyId\",\"watermarkParam\":\"updatedSince\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(
                IntegrationProtocol.REST,
                "https://api.example.com",
                extractionConfigJson,
                "{\"cronExpression\":\"0 */10 * * * *\"}");

        ResolvedSecret secret = ResolvedSecret.bearer("secret/sap/hana", "token-123");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(genericRestAdapter.extract(any(IntegrationProfile.class), any(ExtractionConfig.class), eq(secret), eq(Instant.EPOCH)))
                .thenThrow(new IllegalArgumentException("REST adapter boom"));

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(dataSource);
        when(genericJdbcAdapter.extract(any(NamedParameterJdbcTemplate.class), any(ExtractionConfig.class), eq(Instant.EPOCH)))
                .thenReturn(List.of(Map.of("legacyId", "CLI-001", "updated_at", java.sql.Timestamp.from(Instant.parse("2026-08-01T10:00:00Z")))));
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

        assertThatThrownBy(() -> orchestrator.run(profile)).isInstanceOf(IntegrationSyncException.class);

        verify(syncStateRecorder).recordFailure(eq(profileId), any(Instant.class), anyString());
        verify(syncStateRepository, never()).upsert(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void recordsFailureAndDoesNotWriteToOutboxWhenExtractionFails() {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"CardCode\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\"}");

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> orchestrator.run(profile)).isInstanceOf(IntegrationSyncException.class);

        verify(outboxRepository, never()).save(any());
        verify(syncStateRepository, never()).upsert(any());
        verify(syncStateRecorder).recordFailure(eq(profileId), any(Instant.class), anyString());
        verify(metrics).recordSyncFailure(eq(tenantId.toString()), eq("customers"), anyString());
    }

    @Test
    void failsFastWhenExtractionConfigIsMissing() {
        IntegrationProfile profile = profileWith(null, "{\"cronExpression\":\"0 */10 * * * *\"}");

        assertThatThrownBy(() -> orchestrator.run(profile)).isInstanceOf(IntegrationSyncException.class);

        verify(syncStateRecorder).recordFailure(eq(profileId), any(Instant.class), anyString());
        verify(jdbcDataSourceFactory, never()).create(anyString(), any());
    }

    @Test
    void recordsCancelledAndThrowsSyncExecutionCancelledExceptionWhenInterruptedDuringProcessing() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode, updated_at FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"CardCode\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\"}");

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(dataSource);

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(
                Map.of("CardCode", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp))
        );
        when(genericJdbcAdapter.extract(any(), any(), eq(Instant.EPOCH))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return rows;
        });

        try {
            assertThatThrownBy(() -> orchestrator.run(profile))
                    .isInstanceOf(SyncExecutionCancelledException.class)
                    .hasMessageContaining("Execution was cancelled for profile " + profileId);

            verify(syncStateRecorder).recordCancelled(eq(profileId), any(Instant.class), anyString());
            verify(outboxRepository, never()).save(any());
            verify(syncStateRepository, never()).upsert(any());
        } finally {
            Thread.interrupted(); // Clear interrupted status for the current test runner thread
        }
    }

    @Test
    void skipsDuplicateOutboxEventWhenPayloadIsIdenticalToLatestSavedEvent() throws Exception {
        String extractionConfigJson = "{\"query\":\"SELECT CardCode, updated_at FROM customers WHERE updated_at >= :lastSyncWithBuffer\","
                + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"CardCode\",\"watermarkColumn\":\"updated_at\"}";
        IntegrationProfile profile = profileWith(extractionConfigJson, "{\"cronExpression\":\"0 */10 * * * *\",\"overlapBufferSeconds\":300}");

        ResolvedSecret secret = ResolvedSecret.basic("secret/sap/hana", "user", "pass");
        when(secretResolver.resolve("secret/sap/hana", tenantId)).thenReturn(secret);
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        HikariDataSource dataSource = mock(HikariDataSource.class, org.mockito.Mockito.withSettings().lenient());
        when(jdbcDataSourceFactory.create(anyString(), eq(secret))).thenReturn(dataSource);

        Instant rowTimestamp = Instant.parse("2026-08-01T10:00:00Z");
        List<Map<String, Object>> rows = List.of(
                Map.of("CardCode", "CLI-001", "updated_at", java.sql.Timestamp.from(rowTimestamp))
        );
        when(genericJdbcAdapter.extract(any(), any(), eq(Instant.EPOCH))).thenReturn(rows);
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{\"customerId\":\"CLI-001\"}");

        // Existing event with identical payload already present
        OutboxEvent existingEvent = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Customer", "customers.upserted", "integration.customers.events", "{\"customerId\":\"CLI-001\"}");
        when(outboxRepository.findLatestByAggregateId(eq(tenantId), any(UUID.class))).thenReturn(Optional.of(existingEvent));

        orchestrator.run(profile);

        // Verify outboxRepository.save was NOT called because it's a duplicate
        verify(outboxRepository, never()).save(any());
        // Verify watermark still advances properly
        verify(syncStateRepository).upsert(any());
    }
}
