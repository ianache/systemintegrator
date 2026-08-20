package com.cl2.integration.integration.outbound;

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
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class OutboundEventDispatchIntegrationTest extends IntegrationApplicationTest {

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
    @DisplayName("Case 1: Matching outbound profile exists and target returns 200 OK -> InboxStore records PROCESSED and WireMock receives transformed payload")
    void shouldSuccessfullyDispatchOutboundEventWhenTargetReturns200() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String credentialRef = "vault:secret/data/crm";
        String endpoint = baseUrl + "/api/v1/customers";

        // Setup secret in SecretResolver
        ResolvedSecret secret = ResolvedSecret.bearer(credentialRef, "test-oauth-bearer-token-123");
        secretResolver.putSecret(credentialRef, tenantId, secret);

        // Configure mapping: extract id -> externalId, name -> companyName
        String mappingJson = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "externalId", "sourcePath": "$.id" },
                { "target": "companyName", "sourcePath": "$.name" }
              ]
            }
            """;

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "crm-connector",
                "crm-adapter",
                endpoint,
                credentialRef,
                mappingJson,
                null,
                null,
                null,
                null,
                null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(),
                tenantId,
                "customers",
                "salesforce",
                SyncDirection.OUTBOUND,
                SourceOfTruth.PLATFORM,
                config
        );
        profileRepository.save(tenantId, profile);

        // Stub WireMock target endpoint
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/customers"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer test-oauth-bearer-token-123"))
                .withRequestBody(equalToJson("{\"externalId\":\"cust-100\",\"companyName\":\"Acme Corp\"}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"id\":\"ext-cust-100\"}")));

        // Construct Kafka ConsumerRecord
        String rawPayload = "{\"id\":\"cust-100\",\"name\":\"Acme Corp\"}";
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("X-Tenant-ID", tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("X-Event-Type", "CustomerCreatedEvent".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "integration.events", 0, 0L, 0L, null, 0, 0,
                eventId.toString(), rawPayload, headers, null
        );

        // Trigger Kafka listener
        kafkaInboxListener.onMessage(record);

        // Verify WireMock received the HTTP request
        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/customers"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer test-oauth-bearer-token-123"))
                .withRequestBody(equalToJson("{\"externalId\":\"cust-100\",\"companyName\":\"Acme Corp\"}")));

        // Verify InboxStore marks event as PROCESSED
        Optional<InboxJpaEntity> inboxEntityOpt = inboxStore.find(eventId, tenantId);
        assertThat(inboxEntityOpt).isPresent();
        InboxJpaEntity inboxEntity = inboxEntityOpt.get();
        assertThat(inboxEntity.getStatus()).isEqualTo("PROCESSED");
        assertThat(inboxEntity.getProcessedAt()).isNotNull();
        assertThat(inboxEntity.getLastError()).isNull();
    }

    @Test
    @DisplayName("Case 2: Matching outbound profile target returns 500 Server Error -> InboxStore marks DEAD_LETTER and DLQ receives event")
    void shouldForwardToDlqAndMarkDeadLetterWhenTargetReturns500() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String endpoint = baseUrl + "/api/v1/vehicles";

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "fleet-connector",
                "fleet-adapter",
                endpoint,
                null,
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
                "vehicles",
                "telematics",
                SyncDirection.OUTBOUND,
                SourceOfTruth.PLATFORM,
                config
        );
        profileRepository.save(tenantId, profile);

        // Stub WireMock to return 500 Internal Server Error
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/vehicles"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Remote service unavailable\"}")));

        // Construct Kafka ConsumerRecord
        String rawPayload = "{\"vin\":\"VIN-TEST-999\",\"speed\":65}";
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("X-Tenant-ID", tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("X-Event-Type", "vehicle.speed.alert".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "integration.events", 0, 0L, 0L, null, 0, 0,
                eventId.toString(), rawPayload, headers, null
        );

        // Trigger Kafka listener and expect RuntimeException forwarding to DLQ
        assertThatThrownBy(() -> kafkaInboxListener.onMessage(record))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Inbox processing failed, forwarded to DLQ");

        // Verify WireMock was called
        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/vehicles"))
                .withRequestBody(equalToJson(rawPayload)));

        // Verify InboxStore marks event as DEAD_LETTER
        Optional<InboxJpaEntity> inboxEntityOpt = inboxStore.find(eventId, tenantId);
        assertThat(inboxEntityOpt).isPresent();
        InboxJpaEntity inboxEntity = inboxEntityOpt.get();
        assertThat(inboxEntity.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(inboxEntity.getLastError()).contains("500");
        assertThat(inboxEntity.getAttempts()).isGreaterThanOrEqualTo(1);

        // Verify DeadLetterQueuePublisher sends failure event to DLQ Kafka topic
        ArgumentCaptor<ProducerRecord<String, String>> dlqCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(dlqCaptor.capture());

        ProducerRecord<String, String> dlqRecord = dlqCaptor.getValue();
        assertThat(dlqRecord.topic()).isEqualTo("integration.events.dlq");
        assertThat(dlqRecord.key()).isEqualTo(eventId.toString());
        assertThat(dlqRecord.value()).isEqualTo(rawPayload);

        Header tenantHeader = dlqRecord.headers().lastHeader("X-Tenant-ID");
        assertThat(tenantHeader).isNotNull();
        assertThat(new String(tenantHeader.value(), StandardCharsets.UTF_8)).isEqualTo(tenantId.toString());

        Header errorHeader = dlqRecord.headers().lastHeader("X-Error-Message");
        assertThat(errorHeader).isNotNull();
        assertThat(new String(errorHeader.value(), StandardCharsets.UTF_8)).contains("500");
    }

    @Test
    @DisplayName("Case 3: Reprocessing an already PROCESSED event is idempotent and does not dispatch HTTP call again")
    void shouldHandleDuplicateEventsIdempotently() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String endpoint = baseUrl + "/api/v1/customers";

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "crm-connector", "crm-adapter", endpoint, null, null, null, null, null, null, null
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "customers", "crm", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );
        profileRepository.save(tenantId, profile);

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/customers"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        String payload = "{\"name\":\"Bob\"}";
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("X-Tenant-ID", tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("X-Event-Type", "customer.created".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "integration.events", 0, 0L, 0L, null, 0, 0,
                eventId.toString(), payload, headers, null
        );

        // First execution: processed
        kafkaInboxListener.onMessage(record);
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/api/v1/customers")));

        // Second execution: duplicate should be ignored by inboxProcessor
        kafkaInboxListener.onMessage(record);
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/api/v1/customers")));

        Optional<InboxJpaEntity> inboxEntityOpt = inboxStore.find(eventId, tenantId);
        assertThat(inboxEntityOpt).isPresent();
        assertThat(inboxEntityOpt.get().getStatus()).isEqualTo("PROCESSED");
    }
}
