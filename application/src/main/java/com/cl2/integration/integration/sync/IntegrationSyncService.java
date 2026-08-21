package com.cl2.integration.integration.sync;

import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

@Service
public class IntegrationSyncService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncService.class);

    private final IntegrationProfileRepository profileRepository;
    private final IntegrationSyncOrchestrator orchestrator;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Executor integrationSyncExecutor;
    private final IntegrationSyncProperties properties;
    private final Map<UUID, Future<?>> activeExecutions = new ConcurrentHashMap<>();

    public IntegrationSyncService(
            IntegrationProfileRepository profileRepository,
            IntegrationSyncOrchestrator orchestrator,
            LockingTaskExecutor lockingTaskExecutor,
            @Qualifier("integrationSyncExecutor") Executor integrationSyncExecutor,
            IntegrationSyncProperties properties) {
        this.profileRepository = profileRepository;
        this.orchestrator = orchestrator;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.integrationSyncExecutor = integrationSyncExecutor;
        this.properties = properties;
    }

    public void triggerSync(UUID tenantId, UUID profileId) {
        IntegrationProfile profile;
        try {
            profile = profileRepository.findById(tenantId, profileId);
        } catch (IntegrationProfileNotFoundException ex) {
            throw ex;
        }

        if (profile == null) {
            throw new IntegrationProfileNotFoundException("Integration profile was not found: " + profileId);
        }

        if (!profile.active()) {
            throw new IllegalStateException("Integration profile is inactive: " + profileId);
        }

        dispatch(profile);
    }

    public void dispatch(IntegrationProfile profile) {
        LockConfiguration lockConfiguration = new LockConfiguration(
                Instant.now(), "sync:" + profile.id(),
                Duration.ofSeconds(properties.getDefaultRunLockAtMostForSeconds()), Duration.ofSeconds(1));
        Runnable task = () -> orchestrator.run(profile);

        if (integrationSyncExecutor instanceof java.util.concurrent.ExecutorService executorService) {
            Future<?> future = executorService.submit(() -> {
                try {
                    lockingTaskExecutor.executeWithLock(task, lockConfiguration);
                } catch (Exception ex) {
                    log.warn("Sync run failed for profile {}: {}", profile.id(), ex.getMessage());
                } finally {
                    activeExecutions.remove(profile.id());
                }
            });
            activeExecutions.put(profile.id(), future);
        } else {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    lockingTaskExecutor.executeWithLock(task, lockConfiguration);
                } catch (Exception ex) {
                    log.warn("Sync run failed for profile {}: {}", profile.id(), ex.getMessage());
                } finally {
                    activeExecutions.remove(profile.id());
                }
            }, integrationSyncExecutor);
            activeExecutions.put(profile.id(), future);
        }
    }

    public void cancelRunningExecution(UUID profileId) {
        Future<?> future = activeExecutions.remove(profileId);
        if (future != null && !future.isDone()) {
            log.info("Canceling active sync execution for profileId={}", profileId);
            future.cancel(true);
        }
    }
}
