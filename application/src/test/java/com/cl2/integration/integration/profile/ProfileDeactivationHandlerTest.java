package com.cl2.integration.integration.profile;

import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import com.cl2.integration.integration.sync.IntegrationSyncService;
import com.cl2.integration.integration.sync.SyncStateRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ProfileDeactivationHandlerTest {

    @Test
    @DisplayName("Should cancel active sync and pending outbox events on IntegrationProfileDeactivated event")
    void shouldHandleProfileDeactivation() {
        IntegrationSyncService syncService = mock(IntegrationSyncService.class);
        SpringDataOutboxRepository outboxRepository = mock(SpringDataOutboxRepository.class);
        SyncStateRecorder syncStateRecorder = mock(SyncStateRecorder.class);

        ProfileDeactivationHandler handler = new ProfileDeactivationHandler(
                syncService,
                outboxRepository,
                syncStateRecorder
        );

        UUID profileId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        IntegrationProfileView view = new IntegrationProfileView(
                profileId, tenantId, "units", "sigo", SyncDirection.INBOUND,
                SourceOfTruth.PLATFORM, new IntegrationProfileConfiguration(IntegrationProtocol.JDBC, "connector", "adapter", "url", "ref", null, null, null, null, null, null),
                false, Instant.now(), Instant.now(), 1L
        );

        IntegrationProfileEvent event = new IntegrationProfileEvent(
                UUID.randomUUID(), "IntegrationProfileDeactivated", profileId, tenantId, Instant.now(), view
        );

        handler.onProfileDeactivated(event);

        verify(syncService).cancelRunningExecution(profileId);
        verify(outboxRepository).cancelPendingByTenantAndTopic(tenantId, "integration.units.events", "Profile deactivated");
        verify(syncStateRecorder).recordCancelled(eq(profileId), any(), eq("Profile deactivated"));
    }

    @Test
    @DisplayName("Should ignore events that are not IntegrationProfileDeactivated")
    void shouldIgnoreOtherEventTypes() {
        IntegrationSyncService syncService = mock(IntegrationSyncService.class);
        SpringDataOutboxRepository outboxRepository = mock(SpringDataOutboxRepository.class);
        SyncStateRecorder syncStateRecorder = mock(SyncStateRecorder.class);

        ProfileDeactivationHandler handler = new ProfileDeactivationHandler(
                syncService,
                outboxRepository,
                syncStateRecorder
        );

        UUID profileId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        IntegrationProfileView view = new IntegrationProfileView(
                profileId, tenantId, "units", "sigo", SyncDirection.INBOUND,
                SourceOfTruth.PLATFORM, new IntegrationProfileConfiguration(IntegrationProtocol.JDBC, "connector", "adapter", "url", "ref", null, null, null, null, null, null),
                true, Instant.now(), Instant.now(), 1L
        );

        IntegrationProfileEvent event = new IntegrationProfileEvent(
                UUID.randomUUID(), "IntegrationProfileActivated", profileId, tenantId, Instant.now(), view
        );

        handler.onProfileDeactivated(event);

        verifyNoInteractions(syncService, outboxRepository, syncStateRecorder);
    }
}
