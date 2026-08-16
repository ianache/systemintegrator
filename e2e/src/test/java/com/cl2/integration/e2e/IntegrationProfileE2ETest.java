package com.cl2.integration.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cl2.integration.adapter.in.web.dto.IntegrationProfileResponse;
import com.cl2.integration.integration.profile.IntegrationProfileEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class IntegrationProfileE2ETest extends E2eApplicationTest {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers:127.0.0.1:29092}")
    private String bootstrapServers;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(restTemplate, port);
    }

    @Test
    void isolatesTenantProfilesAcrossTheFullVersionedLifecycleAndPublishesCurrentStateEvents() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        try (KafkaEventObserver events = new KafkaEventObserver(
                bootstrapServers, "integration-profile.events", objectMapper)) {
            ResponseEntity<IntegrationProfileResponse> tenantACreated = api.create(tenantA, "orders", "erp-a");
            ResponseEntity<IntegrationProfileResponse> tenantBCreated = api.create(tenantB, "billing", "erp-b");

            assertThat(tenantACreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(tenantACreated.getBody()).isNotNull();
            assertThat(tenantBCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(tenantBCreated.getBody()).isNotNull();
            IntegrationProfileResponse tenantAProfile = tenantACreated.getBody();
            IntegrationProfileResponse tenantBProfile = tenantBCreated.getBody();
            assertThat(tenantAProfile.tenantId()).isEqualTo(tenantA);
            assertThat(tenantBProfile.tenantId()).isEqualTo(tenantB);

            assertThat(api.list(tenantA, true).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.list(tenantA, true).getBody()).extracting(IntegrationProfileResponse::id)
                    .containsExactly(tenantAProfile.id());
            assertThat(api.get(tenantA, tenantAProfile.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.get(tenantA, tenantAProfile.id()).getBody()).isEqualTo(tenantAProfile);
            assertThat(api.get(tenantB, tenantAProfile.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(api.list(tenantB, true).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.list(tenantB, true).getBody()).extracting(IntegrationProfileResponse::id)
                    .containsExactly(tenantBProfile.id());

            assertEvent(events.await(tenantAProfile.id(), tenantA, "IntegrationProfileCreated", EVENT_TIMEOUT),
                    tenantAProfile.id(), tenantA, "IntegrationProfileCreated", true, 0);

            ResponseEntity<IntegrationProfileResponse> updated = api.update(
                    tenantA, tenantAProfile.id(), "orders-v2", "erp-a-v2", tenantAProfile.version());

            assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(updated.getBody()).isNotNull();
            IntegrationProfileResponse updatedProfile = updated.getBody();
            assertThat(updatedProfile.id()).isEqualTo(tenantAProfile.id());
            assertThat(updatedProfile.tenantId()).isEqualTo(tenantA);
            assertThat(updatedProfile.active()).isTrue();
            assertThat(updatedProfile.version()).isEqualTo(1);
            assertEvent(events.await(updatedProfile.id(), tenantA, "IntegrationProfileUpdated", EVENT_TIMEOUT),
                    updatedProfile.id(), tenantA, "IntegrationProfileUpdated", true, 1);

            assertThat(api.deactivate(tenantA, updatedProfile.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertEvent(events.await(updatedProfile.id(), tenantA, "IntegrationProfileDeactivated", EVENT_TIMEOUT),
                    updatedProfile.id(), tenantA, "IntegrationProfileDeactivated", false, 2);

            assertThat(api.list(tenantA, true).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.list(tenantA, true).getBody()).isEmpty();
            assertThat(api.list(tenantA, false).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.list(tenantA, false).getBody()).singleElement().satisfies(profile -> {
                assertThat(profile.id()).isEqualTo(updatedProfile.id());
                assertThat(profile.active()).isFalse();
                assertThat(profile.version()).isEqualTo(2);
            });
            assertThat(api.get(tenantA, updatedProfile.id()).getBody()).satisfies(profile -> {
                assertThat(profile.active()).isFalse();
                assertThat(profile.version()).isEqualTo(2);
            });
        }
    }

    private void assertEvent(
            IntegrationProfileEvent event,
            UUID profileId,
            UUID tenantId,
            String eventType,
            boolean active,
            long version) {
        assertThat(event.profileId()).isEqualTo(profileId);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.eventType()).isEqualTo(eventType);
        assertThat(event.state().id()).isEqualTo(profileId);
        assertThat(event.state().tenantId()).isEqualTo(tenantId);
        assertThat(event.state().active()).isEqualTo(active);
        assertThat(event.state().version()).isEqualTo(version);
    }
}
