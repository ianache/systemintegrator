package com.cl2.integration.integration.sync;

import com.cl2.integration.adapter.out.generic.GenericJdbcAdapter;
import com.cl2.integration.adapter.out.generic.GenericRestAdapter;
import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.integration.batch.BatchContext;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxRepository;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.transformation.TransformationService;
import com.cl2.integration.infrastructure.metrics.IntegrationMetrics;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class IntegrationSyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncOrchestrator.class);

    private final SecretResolver secretResolver;
    private final JdbcDataSourceFactory jdbcDataSourceFactory;
    private final GenericJdbcAdapter genericJdbcAdapter;
    private final GenericRestAdapter genericRestAdapter;
    private final TransformationService transformationService;
    private final ResilienceExecutor resilienceExecutor;
    private final OutboxRepository outboxRepository;
    private final SyncStateRepository syncStateRepository;
    private final SyncStateRecorder syncStateRecorder;
    private final ObjectMapper objectMapper;
    private final IntegrationMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public IntegrationSyncOrchestrator(
            SecretResolver secretResolver,
            JdbcDataSourceFactory jdbcDataSourceFactory,
            GenericJdbcAdapter genericJdbcAdapter,
            GenericRestAdapter genericRestAdapter,
            TransformationService transformationService,
            ResilienceExecutor resilienceExecutor,
            OutboxRepository outboxRepository,
            SyncStateRepository syncStateRepository,
            SyncStateRecorder syncStateRecorder,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false) IntegrationMetrics metrics) {
        this.secretResolver = secretResolver;
        this.jdbcDataSourceFactory = jdbcDataSourceFactory;
        this.genericJdbcAdapter = genericJdbcAdapter;
        this.genericRestAdapter = genericRestAdapter;
        this.transformationService = transformationService;
        this.resilienceExecutor = resilienceExecutor;
        this.outboxRepository = outboxRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncStateRecorder = syncStateRecorder;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public IntegrationSyncOrchestrator(
            SecretResolver secretResolver,
            JdbcDataSourceFactory jdbcDataSourceFactory,
            GenericJdbcAdapter genericJdbcAdapter,
            TransformationService transformationService,
            ResilienceExecutor resilienceExecutor,
            OutboxRepository outboxRepository,
            SyncStateRepository syncStateRepository,
            SyncStateRecorder syncStateRecorder,
            ObjectMapper objectMapper,
            IntegrationMetrics metrics) {
        this(secretResolver, jdbcDataSourceFactory, genericJdbcAdapter, null, transformationService,
                resilienceExecutor, outboxRepository, syncStateRepository, syncStateRecorder, objectMapper, metrics);
    }

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
        this(secretResolver, jdbcDataSourceFactory, genericJdbcAdapter, null, transformationService,
                resilienceExecutor, outboxRepository, syncStateRepository, syncStateRecorder, objectMapper, null);
    }

    @Transactional
    public void run(IntegrationProfile profile) {
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        try {
            ExtractionConfig extractionConfig = readExtractionConfig(profile);
            ResolvedSecret secret = secretResolver.resolve(profile.configuration().credentialRef(), profile.tenantId());
            Instant watermark = syncStateRepository.find(profile.id())
                    .map(SyncState::lastWatermark)
                    .orElse(Instant.EPOCH);

            IntegrationProtocol protocol = profile.configuration().protocol();
            List<Map<String, Object>> rows = extractRows(profile, extractionConfig, secret, watermark, protocol);
            String keyField = readKeyField(extractionConfig, protocol);
            BatchContext batchContext = extractionConfig.batchMode()
                    ? BatchContext.batch(extractionConfig.batchSize())
                    : BatchContext.unitary();

            String aggregateType = deriveAggregateType(profile.businessDomain());
            String eventType = deriveEventType(profile.businessDomain());
            String topic = deriveTopic(profile.businessDomain());

            Instant maxRowTimestamp = watermark;
            if (batchContext.batchMode()) {
                maxRowTimestamp = findMaxRowTimestamp(rows, extractionConfig.watermarkColumn(), watermark);
                String batchEventType = deriveBatchEventType(profile.businessDomain());
                String batchTopic = deriveBatchTopic(profile.businessDomain());
                List<Map<String, Object>> undeliveredRows = filterUndeliveredRows(
                        profile, rows, keyField, protocol);
                for (List<Map<String, Object>> batch : partitionRows(undeliveredRows, batchContext.batchSize())) {
                    throwIfInterrupted(profile);
                    String batchJson = objectMapper.writeValueAsString(batch);
                    String canonicalJson = requirePublishableBatchPayload(
                            transformationService.transform(batchJson, profile));
                    UUID aggregateId = deriveBatchAggregateId(
                            profile.tenantId(), profile.businessDomain(), batch, keyField, protocol);
                    List<UUID> deliveryIds = batch.stream()
                            .map(row -> deriveDeliveryId(
                                    profile.tenantId(),
                                    profile.businessDomain(),
                                    readBusinessKey(row, keyField, protocol)))
                            .toList();
                    saveIfNotDuplicate(
                            profile,
                            aggregateId,
                            aggregateType,
                            batchEventType,
                            batchTopic,
                            canonicalJson,
                            deliveryIds);
                }
            } else {
                for (Map<String, Object> row : rows) {
                    throwIfInterrupted(profile);
                    String rowJson = objectMapper.writeValueAsString(row);
                    String canonicalJson = transformationService.transform(rowJson, profile);
                    UUID aggregateId = deriveAggregateId(profile.tenantId(), readBusinessKey(row, keyField, protocol));
                    saveIfNotDuplicate(profile, aggregateId, aggregateType, eventType, topic, canonicalJson);

                    Instant rowTimestamp = readWatermarkTimestamp(row, extractionConfig.watermarkColumn());
                    if (rowTimestamp.isAfter(maxRowTimestamp)) {
                        maxRowTimestamp = rowTimestamp;
                    }
                }
            }

            int overlapBufferSeconds = readOverlapBufferSeconds(profile);
            Instant advancedWatermark = rows.isEmpty() ? watermark : maxRowTimestamp.minusSeconds(overlapBufferSeconds);
            syncStateRepository.upsert(new SyncState(profile.id(), advancedWatermark, startedAt, SyncRunStatus.SUCCESS, null));

            double durationSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
            if (metrics != null) {
                metrics.recordSyncRun(
                        profile.tenantId() != null ? profile.tenantId().toString() : "unknown",
                        profile.businessDomain(),
                        profile.externalSource(),
                        "SUCCESS",
                        durationSeconds,
                        rows.size());
            }
        } catch (SyncExecutionCancelledException ex) {
            log.info("Sync run cancelled for profile {}: {}", profile.id(), ex.getMessage());
            syncStateRecorder.recordCancelled(profile.id(), startedAt, ex.getMessage());
            if (metrics != null) {
                metrics.recordSyncFailure(
                        profile.tenantId() != null ? profile.tenantId().toString() : "unknown",
                        profile.businessDomain(),
                        ex.getClass().getSimpleName());
            }
            throw ex;
        } catch (Exception ex) {
            log.warn("Sync run failed for profile {}: {}", profile.id(), ex.getMessage(), ex);
            syncStateRecorder.recordFailure(profile.id(), startedAt, String.valueOf(ex.getMessage()));
            if (metrics != null) {
                metrics.recordSyncFailure(
                        profile.tenantId() != null ? profile.tenantId().toString() : "unknown",
                        profile.businessDomain(),
                        ex.getClass().getSimpleName());
            }
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

    private List<Map<String, Object>> extractRows(
            IntegrationProfile profile,
            ExtractionConfig extractionConfig,
            ResolvedSecret secret,
            Instant watermark,
            IntegrationProtocol protocol
    ) {
        return switch (protocol) {
            case JDBC -> extractJdbcRows(profile, extractionConfig, secret, watermark);
            case REST -> extractRestRows(profile, extractionConfig, secret, watermark);
            default -> throw new IllegalStateException("Unsupported integration protocol for sync orchestration: " + protocol);
        };
    }

    private List<Map<String, Object>> extractJdbcRows(
            IntegrationProfile profile,
            ExtractionConfig extractionConfig,
            ResolvedSecret secret,
            Instant watermark
    ) {
        if (extractionConfig.watermarkColumn() == null || extractionConfig.watermarkColumn().isBlank()) {
            throw new IllegalStateException("extractionConfig.watermarkColumn is required for JDBC profiles");
        }
        try (HikariDataSource dataSource = jdbcDataSourceFactory.create(profile.configuration().endpoint(), secret)) {
            NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
            return resilienceExecutor.execute(profile.tenantId(), profile.configuration().connector(),
                    () -> genericJdbcAdapter.extract(jdbcTemplate, extractionConfig, watermark));
        }
    }

    private List<Map<String, Object>> extractRestRows(
            IntegrationProfile profile,
            ExtractionConfig extractionConfig,
            ResolvedSecret secret,
            Instant watermark
    ) {
        if (genericRestAdapter == null) {
            throw new IllegalStateException("GenericRestAdapter is required for REST profiles");
        }
        return resilienceExecutor.execute(profile.tenantId(), profile.configuration().connector(),
                () -> genericRestAdapter.extract(profile, extractionConfig, secret, watermark));
    }

    private String readKeyField(ExtractionConfig extractionConfig, IntegrationProtocol protocol) {
        return switch (protocol) {
            case JDBC -> extractionConfig.keyColumn();
            case REST -> {
                if (extractionConfig.keyProperty() == null || extractionConfig.keyProperty().isBlank()) {
                    throw new IllegalStateException("extractionConfig.keyProperty is required for REST profiles");
                }
                yield extractionConfig.keyProperty();
            }
            default -> throw new IllegalStateException("Unsupported integration protocol for sync orchestration: " + protocol);
        };
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

    private List<List<Map<String, Object>>> partitionRows(List<Map<String, Object>> rows, int batchSize) {
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        for (int start = 0; start < rows.size(); start += batchSize) {
            int end = Math.min(start + batchSize, rows.size());
            batches.add(new ArrayList<>(rows.subList(start, end)));
        }
        return batches;
    }

    private String deriveBatchEventType(String businessDomain) {
        if (businessDomain == null || businessDomain.isBlank()) {
            return "entity.batch.upserted";
        }
        return businessDomain.trim().toLowerCase() + ".batch.upserted";
    }

    private String deriveBatchTopic(String businessDomain) {
        if (businessDomain == null || businessDomain.isBlank()) {
            return "integration.batch.events";
        }
        return "integration." + businessDomain.trim().toLowerCase() + ".batch.events";
    }

    private UUID deriveBatchAggregateId(
            UUID tenantId,
            String businessDomain,
            List<Map<String, Object>> batch,
            String keyField,
            IntegrationProtocol protocol
    ) {
        StringBuilder identity = new StringBuilder();
        appendLengthPrefixed(identity, String.valueOf(tenantId));
        appendLengthPrefixed(identity, businessDomain == null ? "" : businessDomain);
        for (Map<String, Object> row : batch) {
            appendLengthPrefixed(identity, readBusinessKey(row, keyField, protocol));
        }
        return UUID.nameUUIDFromBytes(identity.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> filterUndeliveredRows(
            IntegrationProfile profile,
            List<Map<String, Object>> rows,
            String keyField,
            IntegrationProtocol protocol
    ) {
        List<UUID> deliveryIds = rows.stream()
                .map(row -> deriveDeliveryId(
                        profile.tenantId(),
                        profile.businessDomain(),
                        readBusinessKey(row, keyField, protocol)))
                .toList();
        Set<UUID> existingDeliveryIds = new HashSet<>(safeExistingDeliveryIds(
                outboxRepository.findExistingDeliveryIds(profile.tenantId(), deliveryIds)));
        List<Map<String, Object>> undeliveredRows = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (existingDeliveryIds.add(deliveryIds.get(index))) {
                undeliveredRows.add(rows.get(index));
            }
        }
        return undeliveredRows;
    }

    private Collection<UUID> safeExistingDeliveryIds(Collection<UUID> deliveryIds) {
        return deliveryIds != null ? deliveryIds : List.of();
    }

    private UUID deriveDeliveryId(UUID tenantId, String businessDomain, String businessKey) {
        StringBuilder identity = new StringBuilder();
        appendLengthPrefixed(identity, String.valueOf(tenantId));
        appendLengthPrefixed(identity, businessDomain == null ? "" : businessDomain);
        appendLengthPrefixed(identity, businessKey);
        return UUID.nameUUIDFromBytes(identity.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String requirePublishableBatchPayload(String payload) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isArray() || node.isEmpty()) {
                throw new IllegalStateException("Batch transformation output must be a non-empty JSON array");
            }
            return payload;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Batch transformation output must be a non-empty JSON array",
                    exception);
        }
    }

    private void appendLengthPrefixed(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private void throwIfInterrupted(IntegrationProfile profile) {
        if (Thread.currentThread().isInterrupted()) {
            log.info("Sync execution interrupted for profileId={} (tenantId={})", profile.id(), profile.tenantId());
            throw new SyncExecutionCancelledException("Execution was cancelled for profile " + profile.id());
        }
    }

    private void saveIfNotDuplicate(
            IntegrationProfile profile,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String topic,
            String payload
    ) {
        saveIfNotDuplicate(profile, aggregateId, aggregateType, eventType, topic, payload, List.of());
    }

    private void saveIfNotDuplicate(
            IntegrationProfile profile,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String topic,
            String payload,
            List<UUID> deliveryIds
    ) {
        boolean isDuplicate = outboxRepository.findLatestByAggregateId(profile.tenantId(), aggregateId)
                .map(latestEvent -> payload.equals(latestEvent.payload()))
                .orElse(false);

        if (!isDuplicate) {
            OutboxEvent event = OutboxEvent.pending(
                    profile.tenantId(),
                    aggregateId,
                    aggregateType,
                    eventType,
                    topic,
                    payload,
                    profile.externalSource());
            if (deliveryIds.isEmpty()) {
                outboxRepository.save(event);
            } else {
                outboxRepository.save(event, deliveryIds);
            }
            if (metrics != null) {
                metrics.recordOutboxEventSaved(
                        profile.tenantId() != null ? profile.tenantId().toString() : "unknown",
                        profile.businessDomain(),
                        eventType);
            }
        } else {
            log.debug("Skipping duplicate outbox event for tenantId={}, aggregateId={}, businessDomain={}",
                    profile.tenantId(), aggregateId, profile.businessDomain());
        }
    }

    private Instant findMaxRowTimestamp(List<Map<String, Object>> rows, String watermarkColumn, Instant watermark) {
        Instant maxRowTimestamp = watermark;
        for (Map<String, Object> row : rows) {
            Instant rowTimestamp = readWatermarkTimestamp(row, watermarkColumn);
            if (rowTimestamp.isAfter(maxRowTimestamp)) {
                maxRowTimestamp = rowTimestamp;
            }
        }
        return maxRowTimestamp;
    }

    private String readBusinessKey(Map<String, Object> row, String keyField, IntegrationProtocol protocol) {
        Object keyValue = row.get(keyField);
        if (protocol == IntegrationProtocol.REST && keyValue == null) {
            throw new IllegalStateException("REST row is missing keyProperty '" + keyField + "'");
        }
        return String.valueOf(keyValue);
    }

    private String deriveAggregateType(String businessDomain) {
        if (businessDomain == null || businessDomain.isBlank()) {
            return "Unknown";
        }
        return businessDomain.trim();
    }

    private String deriveEventType(String businessDomain) {
        if (businessDomain == null || businessDomain.isBlank()) {
            return "entity.upserted";
        }
        return businessDomain.trim().toLowerCase() + ".upserted";
    }

    private String deriveTopic(String businessDomain) {
        if (businessDomain == null || businessDomain.isBlank()) {
            return "integration.events";
        }
        return "integration." + businessDomain.trim().toLowerCase() + ".events";
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
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text);
            } catch (java.time.format.DateTimeParseException ignored) {
                try {
                    return OffsetDateTime.parse(text).toInstant();
                } catch (java.time.format.DateTimeParseException ignoredOffset) {
                    try {
                        return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
                    } catch (java.time.format.DateTimeParseException ignoredLocal) {
                        // Fall through to the consistent unsupported-value error below.
                    }
                }
            }
        }
        throw new IllegalStateException("Unsupported watermark column type: "
                + (value == null ? "null" : value.getClass()));
    }
}
