package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class IntegrationSyncEndToEndTest {

    private static final UUID JDBC_TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111151");
    private static final UUID REST_TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111152");
    private static final Instant PREVIOUS_WATERMARK = Instant.parse("2026-08-01T10:15:30Z");
    private static final Instant REST_ROW_WATERMARK = Instant.parse("2026-08-01T10:20:30Z");

    @Container
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("integration")
            .withUsername("integration")
            .withPassword("integration");

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

    private WireMockServer wireMockServer;

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

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
        jdbcTemplate.update("DELETE FROM integration_outbox WHERE aggregate_type = 'customers'");
        jdbcTemplate.update("DELETE FROM integration_sync_state");
        jdbcTemplate.update("DELETE FROM integration_profile");
    }

    @AfterEach
    void tearDownWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    void aDueJdbcProfileExtractsTransformsAndPublishesToTheOutbox() {
        String credentialRef = "secret/test/" + JDBC_TENANT_ID;
        secretResolver.putSecret(credentialRef, JDBC_TENANT_ID, ResolvedSecret.basic(credentialRef, "integration", "integration"));

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
        Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
        IntegrationProfile profile = integrationProfileRepository.save(JDBC_TENANT_ID, IntegrationProfile.rehydrate(
                UUID.randomUUID(), JDBC_TENANT_ID, "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config, true, tenMinutesAgo, tenMinutesAgo, 0));

        scheduler.tick();

        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    List<Map<String, Object>> outboxRows = jdbcTemplate.queryForList(
                            "SELECT * FROM integration_outbox WHERE tenant_id = UNHEX(REPLACE(?, '-', '')) AND aggregate_type = 'customers'",
                            JDBC_TENANT_ID.toString());
                    assertThat(outboxRows).hasSize(1);
                    assertThat(String.valueOf(outboxRows.get(0).get("payload"))).contains("Acme Corp");
                });

        SyncState syncState = fetchSyncState(profile.id());
        assertThat(syncState.lastRunStatus()).isEqualTo(SyncRunStatus.SUCCESS);
        assertThat(syncState.lastWatermark()).isAfterOrEqualTo(Instant.now().minus(5, ChronoUnit.MINUTES));
    }

    @Test
    void aDueRestProfileSynchronizesTransformedRowsIntoTheOutbox() {
        startWireMock();

        String credentialRef = "secret/test/rest/" + REST_TENANT_ID;
        secretResolver.putSecret(credentialRef, REST_TENANT_ID, ResolvedSecret.bearer(credentialRef, "rest-token"));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers"))
                .withQueryParam("updatedSince", equalTo(PREVIOUS_WATERMARK.toString()))
                .willReturn(okJson("""
                        {"items":[{"id":"REST-001","name":"Rest Customer","updatedAt":"2026-08-01T10:20:30Z"}]}
                        """)));

        IntegrationProfile profile = saveRestProfile(wireMockServer.baseUrl(), credentialRef);
        seedSyncState(profile.id(), PREVIOUS_WATERMARK, SyncRunStatus.SUCCESS);

        scheduler.tick();

        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers"))
                            .withQueryParam("updatedSince", equalTo(PREVIOUS_WATERMARK.toString())));

                    List<Map<String, Object>> outboxRows = jdbcTemplate.queryForList(
                            "SELECT tenant_id, aggregate_type, topic, payload FROM integration_outbox WHERE tenant_id = UNHEX(REPLACE(?, '-', '')) AND aggregate_type = 'customers'",
                            REST_TENANT_ID.toString());
                    assertThat(outboxRows).hasSize(1);
                    Map<String, Object> row = outboxRows.get(0);
                    assertThat(asUuid(row.get("tenant_id"))).isEqualTo(REST_TENANT_ID);
                    assertThat(row.get("aggregate_type")).isEqualTo("customers");
                    assertThat(row.get("topic")).isEqualTo("integration.customers.events");
                    assertThat(String.valueOf(row.get("payload"))).isEqualTo("{\"customerId\":\"REST-001\",\"legalName\":\"Rest Customer\"}");
                });

        SyncState syncState = fetchSyncState(profile.id());
        assertThat(syncState.lastRunStatus()).isEqualTo(SyncRunStatus.SUCCESS);
        assertThat(syncState.lastWatermark()).isEqualTo(REST_ROW_WATERMARK);
    }

    @Test
    void aRestSyncFailureKeepsPreviousWatermarkAndDoesNotWriteANewOutboxEvent() {
        startWireMock();

        String credentialRef = "secret/test/rest/" + REST_TENANT_ID;
        secretResolver.putSecret(credentialRef, REST_TENANT_ID, ResolvedSecret.bearer(credentialRef, "rest-token"));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers"))
                .withQueryParam("updatedSince", equalTo(PREVIOUS_WATERMARK.toString()))
                .willReturn(serverError().withBody("{\"error\":\"upstream failure\"}")));

        IntegrationProfile profile = saveRestProfile(wireMockServer.baseUrl(), credentialRef);
        seedSyncState(profile.id(), PREVIOUS_WATERMARK, SyncRunStatus.SUCCESS);

        scheduler.tick();

        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers"))
                            .withQueryParam("updatedSince", equalTo(PREVIOUS_WATERMARK.toString())));

                    Integer outboxCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM integration_outbox WHERE tenant_id = UNHEX(REPLACE(?, '-', '')) AND aggregate_type = 'customers'",
                            Integer.class,
                            REST_TENANT_ID.toString());
                    assertThat(outboxCount).isZero();
                });

        SyncState syncState = fetchSyncState(profile.id());
        assertThat(syncState.lastRunStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(syncState.lastWatermark()).isEqualTo(PREVIOUS_WATERMARK);
    }

    private void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    private IntegrationProfile saveRestProfile(String endpoint, String credentialRef) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "generic-rest",
                "generic-rest-adapter",
                endpoint,
                credentialRef,
                null,
                "{\"engine\":\"JSLT\",\"script\":\"{\\\"customerId\\\": .id, \\\"legalName\\\": .name}\"}",
                "{\"cronExpression\":\"0 * * * * *\",\"overlapBufferSeconds\":0}",
                null,
                null,
                "{\"method\":\"GET\",\"path\":\"/api/customers\",\"queryParams\":{\"updatedSince\":\":updatedSince\"},\"watermarkParam\":\"updatedSince\",\"watermarkFormat\":\"ISO_8601\",\"responseJsonPath\":\"$.items[*]\",\"keyProperty\":\"id\",\"watermarkColumn\":\"updatedAt\"}"
        );
        Instant dueSince = Instant.now().minus(10, ChronoUnit.MINUTES);
        return integrationProfileRepository.save(REST_TENANT_ID, IntegrationProfile.rehydrate(
                UUID.randomUUID(),
                REST_TENANT_ID,
                "customers",
                "crm-rest",
                SyncDirection.INBOUND,
                SourceOfTruth.EXTERNAL,
                config,
                true,
                dueSince,
                dueSince,
                0));
    }

    private void seedSyncState(UUID profileId, Instant watermark, SyncRunStatus status) {
        jdbcTemplate.update(
                "INSERT INTO integration_sync_state (profile_id, last_watermark, last_run_started_at, last_run_status, last_error) VALUES (UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?)",
                profileId.toString(),
                java.sql.Timestamp.from(watermark),
                java.sql.Timestamp.from(watermark),
                status.name(),
                null
        );
    }

    private SyncState fetchSyncState(UUID profileId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_run_status, last_watermark FROM integration_sync_state WHERE profile_id = UNHEX(REPLACE(?, '-', ''))",
                (rs, rowNum) -> new SyncState(profileId,
                        rs.getTimestamp("last_watermark").toInstant(),
                        null,
                        SyncRunStatus.valueOf(rs.getString("last_run_status")),
                        null),
                profileId.toString());
    }

    private UUID asUuid(Object value) {
        byte[] bytes = (byte[]) value;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
