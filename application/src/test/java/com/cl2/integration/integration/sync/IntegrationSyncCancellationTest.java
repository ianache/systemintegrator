package com.cl2.integration.integration.sync;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IntegrationSyncCancellationTest {

    @Test
    @DisplayName("Should cancel active future when cancelRunningExecution is invoked")
    void shouldCancelActiveFuture() throws Exception {
        IntegrationSyncOrchestrator orchestrator = mock(IntegrationSyncOrchestrator.class);
        IntegrationSyncProperties properties = new IntegrationSyncProperties();

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);

        doAnswer(invocation -> {
            taskStarted.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(orchestrator).run(any());

        LockingTaskExecutor lockingTaskExecutor = mock(LockingTaskExecutor.class);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(lockingTaskExecutor).executeWithLock(any(Runnable.class), any());

        IntegrationSyncService service = new IntegrationSyncService(
                mock(com.cl2.integration.domain.port.IntegrationProfileRepository.class),
                orchestrator,
                lockingTaskExecutor,
                Executors.newSingleThreadExecutor(),
                properties
        );

        UUID profileId = UUID.randomUUID();
        IntegrationProfile profile = IntegrationProfile.create(
                profileId,
                UUID.randomUUID(),
                "units",
                "sigo",
                SyncDirection.INBOUND,
                SourceOfTruth.EXTERNAL,
                new IntegrationProfileConfiguration(
                        IntegrationProtocol.JDBC, "generic-jdbc-connector", "generic-jdbc-adapter",
                        "jdbc:mysql://localhost", "cred-ref", null, null, null, null, null,
                        "{\"watermarkColumn\":\"updated_at\",\"keyColumn\":\"id\"}"
                )
        );

        service.dispatch(profile);
        assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();

        service.cancelRunningExecution(profileId);
        assertThat(taskInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }
}
