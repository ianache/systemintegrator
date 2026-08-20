package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationSyncSchedulerTest {

    private IntegrationProfileRepository integrationProfileRepository;
    private SyncStateRepository syncStateRepository;
    private IntegrationSyncService syncService;
    private IntegrationSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        integrationProfileRepository = mock(IntegrationProfileRepository.class);
        syncStateRepository = mock(SyncStateRepository.class);
        syncService = mock(IntegrationSyncService.class);

        scheduler = new IntegrationSyncScheduler(
                integrationProfileRepository, syncStateRepository, syncService, new ObjectMapper());
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

        verify(syncService).dispatch(profile);
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

        verify(syncService, never()).dispatch(any());
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
        doThrow(new RuntimeException("boom")).when(syncService).dispatch(brokenProfile);

        scheduler.tick();

        verify(syncService).dispatch(brokenProfile);
        verify(syncService).dispatch(healthyProfile);
    }
}
