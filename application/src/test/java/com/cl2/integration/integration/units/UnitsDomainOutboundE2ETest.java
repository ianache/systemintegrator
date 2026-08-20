package com.cl2.integration.integration.units;

import com.cl2.integration.IntegrationApplicationTest;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.inbox.InboxJpaEntity;
import com.cl2.integration.integration.inbox.InboxStore;
import com.cl2.integration.integration.inbox.KafkaInboxListener;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

class UnitsDomainOutboundE2ETest extends IntegrationApplicationTest {

    @Autowired
    private KafkaInboxListener kafkaInboxListener;

    @Autowired
    private IntegrationProfileRepository profileRepository;

    @Autowired
    private InboxStore inboxStore;

    @Autowired
    private SecretResolver secretResolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private WireMockServer wireMockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        baseUrl = "http://localhost:" + wireMockServer.port();

        jdbcTemplate.update("DELETE FROM integration_inbox");
        jdbcTemplate.update("DELETE FROM integration_sync_state");
        jdbcTemplate.update("DELETE FROM integration_outbox");
        jdbcTemplate.update("DELETE FROM integration_profile");

        reset(kafkaTemplate);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should fetch Keycloak OAuth2 JWT and dispatch outbound REST POST requests for brands, models, and vehicles")
    void shouldDispatchUnitsDomainEventsWithKeycloakToken() {
        UUID tenantId = UUID.randomUUID();
        String tokenEndpointUrl = baseUrl + "/realms/cl2/protocol/openid-connect/token";

        // Stub Keycloak Token Endpoint
        wireMockServer.stubFor(post(urlEqualTo("/realms/cl2/protocol/openid-connect/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=cl2-integration-client"))
                .withRequestBody(containing("client_secret=cl2-secret-999"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "mock-keycloak-jwt-12345",
                                  "expires_in": 3600,
                                  "token_type": "Bearer"
                                }
                                """)));

        // Stub CL2 Core REST Endpoints for Units domain
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/brands"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer mock-keycloak-jwt-12345"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"created\",\"code\":\"TOYOTA\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/models"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer mock-keycloak-jwt-12345"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"created\",\"code\":\"COROLLA\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/vehicles"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer mock-keycloak-jwt-12345"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"created\",\"vin\":\"VIN-1234567890\"}")));

        // Register OAuth2 Secret in SecretResolver
        String secretRef = "vault:secret/data/units/keycloak";
        ResolvedSecret oauth2Secret = ResolvedSecret.oauth2(
                secretRef,
                tokenEndpointUrl,
                "cl2-integration-client",
                "cl2-secret-999",
                "units:write"
        );
        secretResolver.putSecret(secretRef, tenantId, oauth2Secret);

        // Register Active Outbound REST Profiles for brands, models, vehicles
        createAndSaveProfile(tenantId, "brands", baseUrl + "/api/v1/brands", secretRef);
        createAndSaveProfile(tenantId, "models", baseUrl + "/api/v1/models", secretRef);
        createAndSaveProfile(tenantId, "vehicles", baseUrl + "/api/v1/vehicles", secretRef);

        // 1. Dispatch Brand Event
        UUID brandEventId = UUID.randomUUID();
        String brandPayload = "{\"code\":\"TOYOTA\",\"name\":\"Toyota Motor Corporation\"}";
        sendKafkaEvent(brandEventId, tenantId, "BrandCreatedEvent", "integration.brands.events", brandPayload);

        // 2. Dispatch Model Event
        UUID modelEventId = UUID.randomUUID();
        String modelPayload = "{\"code\":\"COROLLA\",\"brandCode\":\"TOYOTA\",\"name\":\"Corolla Cross\"}";
        sendKafkaEvent(modelEventId, tenantId, "ModelCreatedEvent", "integration.models.events", modelPayload);

        // 3. Dispatch Vehicle Event
        UUID vehicleEventId = UUID.randomUUID();
        String vehiclePayload = "{\"vin\":\"VIN-1234567890\",\"licensePlate\":\"ABC-123\",\"brand\":\"TOYOTA\",\"model\":\"COROLLA\"}";
        sendKafkaEvent(vehicleEventId, tenantId, "VehicleRegisteredEvent", "integration.vehicles.events", vehiclePayload);

        // Assert WireMock received all 3 transformed requests with Bearer JWT
        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/brands"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer mock-keycloak-jwt-12345"))
                .withRequestBody(equalToJson(brandPayload)));

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/models"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer mock-keycloak-jwt-12345"))
                .withRequestBody(equalToJson(modelPayload)));

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/vehicles"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer mock-keycloak-jwt-12345"))
                .withRequestBody(equalToJson(vehiclePayload)));

        // Assert Inbox records are all marked as PROCESSED
        assertInboxProcessed(brandEventId, tenantId);
        assertInboxProcessed(modelEventId, tenantId);
        assertInboxProcessed(vehicleEventId, tenantId);
    }

    private void createAndSaveProfile(UUID tenantId, String domain, String endpoint, String credentialRef) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                domain + "-connector",
                domain + "-adapter",
                endpoint,
                credentialRef,
                null,
                null,
                null,
                null,
                null,
                null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(),
                tenantId,
                domain,
                "cl2-core",
                SyncDirection.OUTBOUND,
                SourceOfTruth.PLATFORM,
                config
        );
        profileRepository.save(tenantId, profile);
    }

    private void sendKafkaEvent(UUID eventId, UUID tenantId, String eventType, String topic, String payload) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("X-Tenant-ID", tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("X-Event-Type", eventType.getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                topic, 0, 0L, 0L, null, 0, 0,
                eventId.toString(), payload, headers, null
        );

        kafkaInboxListener.onMessage(record);
    }

    private void assertInboxProcessed(UUID eventId, UUID tenantId) {
        Optional<InboxJpaEntity> inboxOpt = inboxStore.find(eventId, tenantId);
        assertThat(inboxOpt).as("Inbox entry for event %s should exist", eventId).isPresent();
        InboxJpaEntity entity = inboxOpt.get();
        assertThat(entity.getStatus()).isEqualTo("PROCESSED");
        assertThat(entity.getProcessedAt()).isNotNull();
        assertThat(entity.getLastError()).isNull();
    }
}