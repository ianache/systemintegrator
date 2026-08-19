package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationSyncSchedulerTest {

    private IntegrationProfileRepository integrationProfileRepository;
    private SyncStateRepository syncStateRepository;
    private IntegrationSyncOrchestrator orchestrator;
    private LockingTaskExecutor lockingTaskExecutor;
    private IntegrationSyncProperties properties;
    private IntegrationSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        integrationProfileRepository = mock(IntegrationProfileRepository.class);
        syncStateRepository = mock(SyncStateRepository.class);
        orchestrator = mock(IntegrationSyncOrchestrator.class);
        lockingTaskExecutor = mock(LockingTaskExecutor.class);
        properties = new IntegrationSyncProperties();
        Executor synchronousExecutor = Runnable::run;

        scheduler = new IntegrationSyncScheduler(
                integrationProfileRepository, syncStateRepository, orchestrator,
                lockingTaskExecutor, synchronousExecutor, new ObjectMapper(), properties);

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(lockingTaskExecutor).executeWithLock(any(Runnable.class), any(LockConfiguration.class));
    }

    private IntegrationProfile jdbcProfile(UUID id, String cronExpression, Instant createdAt) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "generic-jdbc", "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration", "secret/sap/hana",
                null, null, "{\"cronExpression\":\"" + cronExpression + "\"}", null, null,
                "{\"query\":\"SELECT 1\",\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"id\",\"watermarkColumn\":\"updated_at\"}");
        return IntegrationProfile.rehydrate(id, UUID.randomUUID(), "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config, true, createdAt, createdAt, 0);
    }

    @Test
    void dispatchesAProfileThatHasNeverRunAndWhoseCronIsAlreadyDue() {
        UUID profileId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS);
        IntegrationProfile profile = jdbcProfile(profileId, "0 * * * * *", createdAt); // every minute
        when(integrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol.JDBC)).thenReturn(List.of(profile));
        when(syncStateRepository.find(profileId)).thenReturn(Optional.empty());

        scheduler.tick();

        verify(orchestrator).run(profile);
    }

    @Test
    void doesNotDispatchAProfileWhoseCronIsNotDueYet() {
        UUID profileId = UUID.randomUUID();
        Instant justRan = Instant.now();
        IntegrationProfile profile = jdbcProfile(profileId, "0 0 0 1 1 *", justRan); // once a year, Jan 1st
        when(integrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol.JDBC)).thenReturn(List.of(profile));
        when(syncStateRepository.find(profileId)).thenReturn(
                Optional.of(new SyncState(profileId, Instant.EPOCH, justRan, SyncRunStatus.SUCCESS, null)));

        scheduler.tick();

        verify(orchestrator, never()).run(any());
    }

    @Test
    void aFailingProfileDoesNotStopTheRestOfTheScan() {
        UUID brokenProfileId = UUID.randomUUID();
        UUID healthyProfileId = UUID.randomUUID();
        Instant longAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        IntegrationProfile brokenProfile = jdbcProfile(brokenProfileId, "0 * * * * *", longAgo);
        IntegrationProfile healthyProfile = jdbcProfile(healthyProfileId, "0 * * * * *", longAgo);
        when(integrationProfileRepository.findAllActiveByProtocol(IntegrationProtocol.JDBC))
                .thenReturn(List.of(brokenProfile, healthyProfile));
        when(syncStateRepository.find(any())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(orchestrator).run(brokenProfile);

        scheduler.tick();

        verify(orchestrator).run(brokenProfile);
        verify(orchestrator).run(healthyProfile);
    }
}
