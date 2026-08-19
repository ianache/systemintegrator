package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class IntegrationSyncEndToEndTest {

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private IntegrationProfileRepository integrationProfileRepository;

    @Autowired
    private SecretResolver secretResolver;

    @Autowired
    private IntegrationSyncScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUpScratchSourceTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS scratch_customers");
        jdbcTemplate.execute("""
                CREATE TABLE scratch_customers (
                    card_code VARCHAR(50) PRIMARY KEY,
                    card_name VARCHAR(200),
                    updated_at TIMESTAMP(6)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO scratch_customers (card_code, card_name, updated_at) VALUES (?, ?, ?)",
                "CLI-001", "Acme Corp", java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
        jdbcTemplate.update("DELETE FROM integration_outbox WHERE aggregate_type = 'Customer'");
    }

    @Test
    void aDueJdbcProfileExtractsTransformsAndPublishesToTheOutbox() {
        String credentialRef = "secret/test/" + tenantId;
        secretResolver.putSecret(credentialRef, tenantId, ResolvedSecret.basic(credentialRef, "integration", "integration"));

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "generic-jdbc", "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
                credentialRef,
                "{\"customerId\":\"card_code\",\"legalName\":\"card_name\"}",
                null,
                "{\"cronExpression\":\"0 * * * * *\",\"overlapBufferSeconds\":0}",
                null, null,
                "{\"query\":\"SELECT card_code, card_name, updated_at FROM scratch_customers WHERE updated_at >= :lastSyncWithBuffer\","
                        + "\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"card_code\",\"watermarkColumn\":\"updated_at\"}");
        IntegrationProfile profile = integrationProfileRepository.save(tenantId, IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config));

        scheduler.tick();

        List<Map<String, Object>> outboxRows = jdbcTemplate.queryForList(
                "SELECT * FROM integration_outbox WHERE tenant_id = UNHEX(REPLACE(?, '-', '')) AND aggregate_type = 'Customer'",
                tenantId.toString());
        assertThat(outboxRows).hasSize(1);
        assertThat(String.valueOf(outboxRows.get(0).get("payload"))).contains("Acme Corp");

        SyncState syncState = jdbcTemplate.queryForObject(
                "SELECT last_run_status, last_watermark FROM integration_sync_state WHERE profile_id = UNHEX(REPLACE(?, '-', ''))",
                (rs, rowNum) -> new SyncState(profile.id(),
                        rs.getTimestamp("last_watermark").toInstant(),
                        null,
                        SyncRunStatus.valueOf(rs.getString("last_run_status")),
                        null),
                profile.id().toString());
        assertThat(syncState.lastRunStatus()).isEqualTo(SyncRunStatus.SUCCESS);
        assertThat(syncState.lastWatermark()).isAfterOrEqualTo(Instant.now().minus(5, ChronoUnit.MINUTES));
    }
}
