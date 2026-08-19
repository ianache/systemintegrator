package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;

@Component
public class IntegrationSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncScheduler.class);

    private final IntegrationProfileRepository integrationProfileRepository;
    private final SyncStateRepository syncStateRepository;
    private final IntegrationSyncOrchestrator orchestrator;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Executor integrationSyncExecutor;
    private final ObjectMapper objectMapper;
    private final IntegrationSyncProperties properties;

    public IntegrationSyncScheduler(
            IntegrationProfileRepository integrationProfileRepository,
            SyncStateRepository syncStateRepository,
            IntegrationSyncOrchestrator orchestrator,
            LockingTaskExecutor lockingTaskExecutor,
            @Qualifier("integrationSyncExecutor") Executor integrationSyncExecutor,
            ObjectMapper objectMapper,
            IntegrationSyncProperties properties) {
        this.integrationProfileRepository = integrationProfileRepository;
        this.syncStateRepository = syncStateRepository;
        this.orchestrator = orchestrator;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.integrationSyncExecutor = integrationSyncExecutor;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${integration.sync.tick-fixed-delay-ms:30000}")
    @SchedulerLock(name = "integration-sync-tick", lockAtMostFor = "25s")
    public void tick() {
        List<IntegrationProfile> profiles = integrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol.JDBC);
        for (IntegrationProfile profile : profiles) {
            try {
                if (isDue(profile)) {
                    dispatch(profile);
                }
            } catch (Exception ex) {
                log.warn("Failed to evaluate or dispatch sync for profile {}: {}", profile.id(), ex.getMessage());
            }
        }
    }

    private boolean isDue(IntegrationProfile profile) {
        SyncPolicy syncPolicy = parseSyncPolicy(profile);
        if (syncPolicy == null || syncPolicy.cronExpression() == null || syncPolicy.cronExpression().isBlank()) {
            return false;
        }
        CronExpression cronExpression = CronExpression.parse(syncPolicy.cronExpression());
        Instant anchorInstant = syncStateRepository.find(profile.id())
                .map(SyncState::lastRunStartedAt)
                .orElse(profile.createdAt());
        LocalDateTime anchor = LocalDateTime.ofInstant(anchorInstant, ZoneOffset.UTC);
        LocalDateTime next = cronExpression.next(anchor);
        return next != null && !next.isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }

    private SyncPolicy parseSyncPolicy(IntegrationProfile profile) {
        String json = profile.configuration() != null ? profile.configuration().syncPolicy() : null;
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SyncPolicy.class);
        } catch (Exception ex) {
            log.warn("Invalid syncPolicy JSON for profile {}: {}", profile.id(), ex.getMessage());
            return null;
        }
    }

    private void dispatch(IntegrationProfile profile) {
        LockConfiguration lockConfiguration = new LockConfiguration(
                Instant.now(), "sync:" + profile.id(),
                Duration.ofSeconds(properties.getDefaultRunLockAtMostForSeconds()), Duration.ofSeconds(1));
        Runnable task = () -> orchestrator.run(profile);
        integrationSyncExecutor.execute(() -> {
            try {
                lockingTaskExecutor.executeWithLock(task, lockConfiguration);
            } catch (Exception ex) {
                log.warn("Sync run failed for profile {}: {}", profile.id(), ex.getMessage());
            }
        });
    }
}
