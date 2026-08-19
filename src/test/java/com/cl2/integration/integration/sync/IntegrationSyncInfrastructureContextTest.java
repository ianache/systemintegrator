package com.cl2.integration.integration.sync;

import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class IntegrationSyncInfrastructureContextTest {

    @Autowired
    private LockingTaskExecutor lockingTaskExecutor;

    @Autowired
    @Qualifier("integrationSyncExecutor")
    private Executor integrationSyncExecutor;

    @Autowired
    private IntegrationSyncProperties properties;

    @Test
    void wiresTheLockingTaskExecutorBean() {
        assertThat(lockingTaskExecutor).isNotNull();
    }

    @Test
    void wiresTheDedicatedSyncExecutorBean() {
        assertThat(integrationSyncExecutor).isNotNull();
    }

    @Test
    void defaultsRunLockAtMostForToTenMinutes() {
        assertThat(properties.getDefaultRunLockAtMostForSeconds()).isEqualTo(600);
    }
}
