package com.cl2.integration.integration.profile;

import com.cl2.integration.IntegrationApplicationTest;
import com.cl2.integration.application.IntegrationProfileService;
import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxJpaEntity;
import com.cl2.integration.integration.outbox.OutboxRelayScheduler;
import com.cl2.integration.integration.outbox.OutboxStatus;
import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import com.cl2.integration.integration.sync.SyncRunStatus;
import com.cl2.integration.integration.sync.SyncState;
import com.cl2.integration.integration.sync.SyncStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileDeactivationIntegrationTest extends IntegrationApplicationTest {

    @Autowired
    private IntegrationProfileService profileService;

    @Autowired
    private SpringDataOutboxRepository outboxRepository;

    @Autowired
    private SyncStateRepository syncStateRepository;

    @Autowired
    private OutboxRelayScheduler outboxRelayScheduler;

    @Test
    @DisplayName("Should cancel pending outbox events and set sync state to CANCELLED on profile deactivation")
    void shouldCancelPendingOutboxEventsAndMarkStateOnDeactivation() {
        UUID tenantId = UUID.randomUUID();
        CreateIntegrationProfileCommand command = new CreateIntegrationProfileCommand(
                "units",
                "sigo-erp",
                SyncDirection.INBOUND,
                SourceOfTruth.PLATFORM,
                new IntegrationProfileConfiguration(
                        IntegrationProtocol.JDBC, "connector", "adapter", "jdbc:mysql://localhost", "cred-ref",
                        null, null, null, null, null, "{\"watermarkColumn\":\"updated_at\",\"keyColumn\":\"id\"}"
                )
        );

        var created = profileService.create(tenantId, command);
        UUID profileId = created.id();

        String topic = "integration.units.events";
        OutboxEvent event1 = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"unit\":1}");
        OutboxEvent event2 = OutboxEvent.pending(tenantId, UUID.randomUUID(), "Unit", "units.upserted", topic, "{\"unit\":2}");
        outboxRepository.save(OutboxJpaEntity.from(event1));
        outboxRepository.save(OutboxJpaEntity.from(event2));

        profileService.deactivate(tenantId, profileId);

        var deactivatedProfile = profileService.get(tenantId, profileId);
        assertThat(deactivatedProfile.active()).isFalse();

        OutboxJpaEntity e1 = outboxRepository.findById(event1.id()).orElseThrow();
        OutboxJpaEntity e2 = outboxRepository.findById(event2.id()).orElseThrow();
        assertThat(e1.toDomain().status()).isEqualTo(OutboxStatus.CANCELLED.name());
        assertThat(e1.toDomain().lastError()).isEqualTo("Profile deactivated");
        assertThat(e2.toDomain().status()).isEqualTo(OutboxStatus.CANCELLED.name());
        assertThat(e2.toDomain().lastError()).isEqualTo("Profile deactivated");

        SyncState syncState = syncStateRepository.find(profileId).orElseThrow();
        assertThat(syncState.lastRunStatus()).isEqualTo(SyncRunStatus.CANCELLED);
        assertThat(syncState.lastError()).isEqualTo("Profile deactivated");
    }
}
