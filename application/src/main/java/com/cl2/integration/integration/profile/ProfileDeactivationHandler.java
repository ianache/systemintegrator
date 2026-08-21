package com.cl2.integration.integration.profile;

import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import com.cl2.integration.integration.sync.IntegrationSyncService;
import com.cl2.integration.integration.sync.SyncStateRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ProfileDeactivationHandler {

    private static final Logger log = LoggerFactory.getLogger(ProfileDeactivationHandler.class);

    private final IntegrationSyncService syncService;
    private final SpringDataOutboxRepository outboxRepository;
    private final SyncStateRecorder syncStateRecorder;

    public ProfileDeactivationHandler(
            IntegrationSyncService syncService,
            SpringDataOutboxRepository outboxRepository,
            SyncStateRecorder syncStateRecorder) {
        this.syncService = syncService;
        this.outboxRepository = outboxRepository;
        this.syncStateRecorder = syncStateRecorder;
    }

    @EventListener
    public void onProfileDeactivated(IntegrationProfileEvent event) {
        if (event == null || !"IntegrationProfileDeactivated".equalsIgnoreCase(event.eventType())) {
            return;
        }

        log.info("Handling profile deactivation for profileId={}, tenantId={}", event.profileId(), event.tenantId());

        // 1. Cancel in-memory running execution if active
        syncService.cancelRunningExecution(event.profileId());

        // 2. Cancel pending outbox events for this profile's domain/topic
        if (event.state() != null && event.state().businessDomain() != null) {
            String topic = "integration." + event.state().businessDomain().trim().toLowerCase() + ".events";
            int cancelled = outboxRepository.cancelPendingByTenantAndTopic(event.tenantId(), topic, "Profile deactivated");
            log.info("Cancelled {} pending outbox event(s) for topic={}", cancelled, topic);
        }

        // 3. Mark sync state as CANCELLED
        syncStateRecorder.recordCancelled(event.profileId(), Instant.now(), "Profile deactivated");
    }
}
