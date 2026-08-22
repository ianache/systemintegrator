package com.cl2.integration.integration.sync;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationSyncOrchestratorTest {

    private SecretResolver secretResolver;
    private JdbcDataSourceFactory jdbcDataSourceFactory;
    private GenericJdbcAdapter genericJdbcAdapter;
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
        transformationService = mock(TransformationService.class);
        resilienceExecutor = mock(ResilienceExecutor.class);
        outboxRepository = mock(OutboxRepository.class);
        syncStateRepository = mock(SyncStateRepository.class);
        syncStateRecorder = mock(SyncStateRecorder.class);
        metrics = mock(com.cl2.integration.infrastructure.metrics.IntegrationMetrics.class);

        orchestrator = new IntegrationSyncOrchestrator(
                secretResolver, jdbcDataSourceFactory, genericJdbcAdapter, transformationService,
                resilienceExecutor, outboxRepository, syncStateRepository, syncStateRecorder, new ObjectMapper(), metrics);

        // ResilienceExecutor just runs the supplier synchronously in these tests
        when(resilienceExecutor.execute(any(), anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(2);
            return supplier.get();
        });
    }

    private IntegrationProfile profileWith(String extractionConfigJson, String syncPolicyJson) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "generic-jdbc", "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration", "secret/sap/hana",
                "{\"customerId\":\"CardCode\"}", null, syncPolicyJson, null, null, extractionConfigJson);
        return IntegrationProfile.rehydrate(profileId, tenantId, "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config, true, Instant.now(), Instant.now(), 0);
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
