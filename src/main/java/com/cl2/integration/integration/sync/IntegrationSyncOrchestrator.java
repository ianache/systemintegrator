package com.cl2.integration.integration.sync;

import com.cl2.integration.adapter.out.generic.GenericJdbcAdapter;
import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxRepository;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.transformation.TransformationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class IntegrationSyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncOrchestrator.class);

    private final SecretResolver secretResolver;
    private final JdbcDataSourceFactory jdbcDataSourceFactory;
    private final GenericJdbcAdapter genericJdbcAdapter;
    private final TransformationService transformationService;
    private final ResilienceExecutor resilienceExecutor;
    private final OutboxRepository outboxRepository;
    private final SyncStateRepository syncStateRepository;
    private final SyncStateRecorder syncStateRecorder;
    private final ObjectMapper objectMapper;

    public IntegrationSyncOrchestrator(
            SecretResolver secretResolver,
            JdbcDataSourceFactory jdbcDataSourceFactory,
            GenericJdbcAdapter genericJdbcAdapter,
            TransformationService transformationService,
            ResilienceExecutor resilienceExecutor,
            OutboxRepository outboxRepository,
            SyncStateRepository syncStateRepository,
            SyncStateRecorder syncStateRecorder,
            ObjectMapper objectMapper) {
        this.secretResolver = secretResolver;
        this.jdbcDataSourceFactory = jdbcDataSourceFactory;
        this.genericJdbcAdapter = genericJdbcAdapter;
        this.transformationService = transformationService;
        this.resilienceExecutor = resilienceExecutor;
        this.outboxRepository = outboxRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncStateRecorder = syncStateRecorder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void run(IntegrationProfile profile) {
        Instant startedAt = Instant.now();
        try {
            ExtractionConfig extractionConfig = readExtractionConfig(profile);
            if (extractionConfig.watermarkColumn() == null || extractionConfig.watermarkColumn().isBlank()) {
                throw new IllegalStateException("extractionConfig.watermarkColumn is required for JDBC profiles");
            }
            ResolvedSecret secret = secretResolver.resolve(profile.configuration().credentialRef(), profile.tenantId());
            Instant watermark = syncStateRepository.find(profile.id())
                    .map(SyncState::lastWatermark)
                    .orElse(Instant.EPOCH);

            List<Map<String, Object>> rows;
            try (HikariDataSource dataSource = jdbcDataSourceFactory.create(profile.configuration().endpoint(), secret)) {
                NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
                rows = resilienceExecutor.execute(profile.tenantId(), profile.configuration().connector(),
                        () -> genericJdbcAdapter.extract(jdbcTemplate, extractionConfig, watermark));
            }

            Instant maxRowTimestamp = watermark;
            for (Map<String, Object> row : rows) {
                String rowJson = objectMapper.writeValueAsString(row);
                String canonicalJson = transformationService.transform(rowJson, profile);
                UUID aggregateId = deriveAggregateId(profile.tenantId(), String.valueOf(row.get(extractionConfig.keyColumn())));
                outboxRepository.save(OutboxEvent.pending(profile.tenantId(), aggregateId, "Customer", "customer.upserted", canonicalJson));

                Instant rowTimestamp = readWatermarkTimestamp(row, extractionConfig.watermarkColumn());
                if (rowTimestamp.isAfter(maxRowTimestamp)) {
                    maxRowTimestamp = rowTimestamp;
                }
            }

            int overlapBufferSeconds = readOverlapBufferSeconds(profile);
            Instant advancedWatermark = rows.isEmpty() ? watermark : maxRowTimestamp.minusSeconds(overlapBufferSeconds);
            syncStateRepository.upsert(new SyncState(profile.id(), advancedWatermark, startedAt, SyncRunStatus.SUCCESS, null));
        } catch (Exception ex) {
            log.warn("Sync run failed for profile {}: {}", profile.id(), ex.getMessage());
            syncStateRecorder.recordFailure(profile.id(), startedAt, String.valueOf(ex.getMessage()));
            throw new IntegrationSyncException("Sync failed for profile " + profile.id(), ex);
        }
    }

    private ExtractionConfig readExtractionConfig(IntegrationProfile profile) {
        String json = profile.configuration() != null ? profile.configuration().extractionConfig() : null;
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Profile " + profile.id() + " has no extractionConfig");
        }
        try {
            return objectMapper.readValue(json, ExtractionConfig.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid extractionConfig JSON for profile " + profile.id(), ex);
        }
    }

    private int readOverlapBufferSeconds(IntegrationProfile profile) {
        String json = profile.configuration() != null ? profile.configuration().syncPolicy() : null;
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            return objectMapper.readValue(json, SyncPolicy.class).overlapBufferSecondsOrZero();
        } catch (Exception ex) {
            return 0;
        }
    }

    private UUID deriveAggregateId(UUID tenantId, String businessKey) {
        return UUID.nameUUIDFromBytes((tenantId + ":" + businessKey).getBytes(StandardCharsets.UTF_8));
    }

    private Instant readWatermarkTimestamp(Map<String, Object> row, String watermarkColumn) {
        Object value = row.get(watermarkColumn);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unsupported watermark column type: "
                + (value == null ? "null" : value.getClass()));
    }
}
