package com.cl2.integration.integration.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class IntegrationProfileEventPublisherTest {

    private static final UUID EVENT_ID = UUID.fromString("8f19a0d6-8c42-4b21-8ed2-c58dcb0f1c39");
    private static final UUID PROFILE_ID = UUID.fromString("63fb75cf-3ca1-4d32-9a1e-7c239269011e");
    private static final UUID TENANT_ID = UUID.fromString("ec7dcc69-0f63-4ae8-9886-5bd69724f2d7");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-15T14:30:00Z");

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Captor
    private ArgumentCaptor<String> payload;

    @Test
    void publishesTheCurrentProfileStateAsJsonUsingTheProfileIdAsKafkaKey() throws Exception {
        IntegrationProfileEvent event = new IntegrationProfileEvent(EVENT_ID, "IntegrationProfileUpdated", PROFILE_ID,
                TENANT_ID, OCCURRED_AT, profileState());

        new IntegrationProfileEventPublisher(kafkaTemplate, new ObjectMapper().findAndRegisterModules()).publish(event);

        verify(kafkaTemplate).send(eq("integration-profile.events"), eq(PROFILE_ID.toString()), payload.capture());
        JsonNode json = new ObjectMapper().readTree(payload.getValue());
        assertThat(json.path("eventId").asText()).isEqualTo(EVENT_ID.toString());
        assertThat(json.path("eventType").asText()).isEqualTo("IntegrationProfileUpdated");
        assertThat(json.path("profileId").asText()).isEqualTo(PROFILE_ID.toString());
        assertThat(json.path("tenantId").asText()).isEqualTo(TENANT_ID.toString());
        assertThat(json.path("occurredAt").asText()).isEqualTo("2026-08-15T14:30:00Z");
        JsonNode state = json.path("state");
        assertThat(state.path("id").asText()).isEqualTo(PROFILE_ID.toString());
        assertThat(state.path("tenantId").asText()).isEqualTo(TENANT_ID.toString());
        assertThat(state.path("businessDomain").asText()).isEqualTo("catalog");
        assertThat(state.path("externalSource").asText()).isEqualTo("crm");
        assertThat(state.path("direction").asText()).isEqualTo("OUTBOUND");
        assertThat(state.path("sourceOfTruth").asText()).isEqualTo("EXTERNAL");
        assertThat(state.path("active").asBoolean()).isTrue();
        assertThat(state.path("createdAt").asText()).isEqualTo("2026-08-15T14:00:00Z");
        assertThat(state.path("updatedAt").asText()).isEqualTo("2026-08-15T14:29:00Z");
        assertThat(state.path("version").asLong()).isEqualTo(4L);
    }

    private IntegrationProfileView profileState() {
        return new IntegrationProfileView(PROFILE_ID, TENANT_ID, "catalog", "crm", SyncDirection.OUTBOUND,
                SourceOfTruth.EXTERNAL, true, Instant.parse("2026-08-15T14:00:00Z"),
                Instant.parse("2026-08-15T14:29:00Z"), 4);
    }
}
