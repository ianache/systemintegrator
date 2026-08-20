package com.cl2.integration.integration.sync;

import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationSyncServiceTest {

    private IntegrationProfileRepository profileRepository;
    private IntegrationSyncOrchestrator orchestrator;
    private LockingTaskExecutor lockingTaskExecutor;
    private IntegrationSyncService syncService;

    @BeforeEach
    void setUp() {
        profileRepository = mock(IntegrationProfileRepository.class);
        orchestrator = mock(IntegrationSyncOrchestrator.class);
        lockingTaskExecutor = mock(LockingTaskExecutor.class);
        Executor directExecutor = Runnable::run;
        IntegrationSyncProperties properties = new IntegrationSyncProperties();

        syncService = new IntegrationSyncService(
                profileRepository, orchestrator, lockingTaskExecutor, directExecutor, properties);

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(lockingTaskExecutor).executeWithLock(any(Runnable.class), any(LockConfiguration.class));
    }

    private IntegrationProfile activeProfile(UUID id, UUID tenantId) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.JDBC, "generic-jdbc", "generic-jdbc-adapter",
                "jdbc:mysql://localhost:3306/integration", "secret/sap/hana",
                null, null, "{\"cronExpression\":\"0 * * * * *\"}", null, null,
                "{\"query\":\"SELECT 1\",\"watermarkParam\":\"last_date\",\"keyColumn\":\"id\",\"watermarkColumn\":\"updated_at\"}");
        return IntegrationProfile.rehydrate(id, tenantId, "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config, true, Instant.now(), Instant.now(), 0);
    }

    @Test
    void triggersSyncForValidActiveProfile() {
        UUID tenantId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        IntegrationProfile profile = activeProfile(profileId, tenantId);
        when(profileRepository.findById(tenantId, profileId)).thenReturn(profile);

        syncService.triggerSync(tenantId, profileId);

        verify(orchestrator).run(profile);
    }

    @Test
    void throwsNotFoundWhenProfileDoesNotExistOrNotFound() {
        UUID tenantId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(profileRepository.findById(tenantId, profileId))
                .thenThrow(new IntegrationProfileNotFoundException("Not found"));

        assertThatThrownBy(() -> syncService.triggerSync(tenantId, profileId))
                .isInstanceOf(IntegrationProfileNotFoundException.class);
    }

    @Test
    void throwsExceptionWhenProfileIsInactive() {
        UUID tenantId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        IntegrationProfile profile = IntegrationProfile.rehydrate(
                profileId, tenantId, "customers", "sap-hana",
                SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, null, false, Instant.now(), Instant.now(), 0);
        when(profileRepository.findById(tenantId, profileId)).thenReturn(profile);

        assertThatThrownBy(() -> syncService.triggerSync(tenantId, profileId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactive");
    }
}
