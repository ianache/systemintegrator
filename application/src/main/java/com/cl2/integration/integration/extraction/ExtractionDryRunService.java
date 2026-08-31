package com.cl2.integration.integration.extraction;

import com.cl2.integration.adapter.out.generic.GenericJdbcAdapter;
import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.application.IntegrationProfileService;
import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.sync.JdbcDataSourceFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual "probar consulta" action from the Extracción SQL tab. Deliberately
 * bypasses ResilienceExecutor (unlike IntegrationSyncOrchestrator's real
 * sync path) — this is a low-frequency, user-triggered test click, and
 * routing it through the same per-tenant circuit breaker as production sync
 * runs would let an ad hoc test query trip (or reset) production state.
 */
@Service
public class ExtractionDryRunService {

    private static final int SAMPLE_LIMIT = 20;

    private final IntegrationProfileService profileService;
    private final SecretResolver secretResolver;
    private final JdbcDataSourceFactory jdbcDataSourceFactory;
    private final GenericJdbcAdapter genericJdbcAdapter;
    private final ObjectMapper objectMapper;

    public ExtractionDryRunService(
            IntegrationProfileService profileService,
            SecretResolver secretResolver,
            JdbcDataSourceFactory jdbcDataSourceFactory,
            GenericJdbcAdapter genericJdbcAdapter,
            ObjectMapper objectMapper) {
        this.profileService = profileService;
        this.secretResolver = secretResolver;
        this.jdbcDataSourceFactory = jdbcDataSourceFactory;
        this.genericJdbcAdapter = genericJdbcAdapter;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ExtractionDryRunResult run(UUID tenantId, UUID profileId) {
        IntegrationProfileView view = profileService.get(tenantId, profileId);
        IntegrationProfileConfiguration config = view.configuration();
        if (config == null || config.protocol() != IntegrationProtocol.JDBC) {
            return ExtractionDryRunResult.failure("El perfil no usa protocolo JDBC; no hay una consulta que probar.");
        }
        if (config.extractionConfig() == null || config.extractionConfig().isBlank()) {
            return ExtractionDryRunResult.failure("El perfil no tiene extractionConfig configurado todavía.");
        }

        ExtractionConfig extractionConfig;
        try {
            extractionConfig = objectMapper.readValue(config.extractionConfig(), ExtractionConfig.class);
        } catch (Exception ex) {
            return ExtractionDryRunResult.failure("extractionConfig no es JSON válido: " + ex.getMessage());
        }
        if (extractionConfig.query() == null || extractionConfig.query().isBlank()) {
            return ExtractionDryRunResult.failure("extractionConfig.query está vacío.");
        }

        try {
            ResolvedSecret secret = secretResolver.resolve(config.credentialRef(), tenantId);
            try (HikariDataSource dataSource = jdbcDataSourceFactory.create(config.endpoint(), secret)) {
                NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
                // Instant.EPOCH so the watermark predicate (`column >= :param`) matches
                // every row — a dry-run has no real watermark to resume from.
                List<Map<String, Object>> rows = genericJdbcAdapter.extract(jdbcTemplate, extractionConfig, Instant.EPOCH);
                List<Map<String, Object>> sample = rows.size() > SAMPLE_LIMIT ? rows.subList(0, SAMPLE_LIMIT) : rows;
                return ExtractionDryRunResult.success(sample, rows.size());
            }
        } catch (Exception ex) {
            return ExtractionDryRunResult.failure(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }
}
