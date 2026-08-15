package com.cl2.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.application.command.UpdateIntegrationProfileCommand;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.profile.IntegrationProfileEvent;
import com.cl2.integration.integration.profile.IntegrationProfileEventListener;
import com.cl2.integration.integration.profile.IntegrationProfileEventPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(IntegrationProfileEventTransactionTest.TestConfiguration.class)
class IntegrationProfileEventTransactionTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID PROFILE_ID = UUID.fromString("63fb75cf-3ca1-4d32-9a1e-7c239269011e");

    @org.springframework.beans.factory.annotation.Autowired
    private IntegrationProfileService service;

    @org.springframework.beans.factory.annotation.Autowired
    private IntegrationProfileRepository repository;

    @org.springframework.beans.factory.annotation.Autowired
    private IntegrationProfileEventPublisher kafkaPublisher;

    @org.springframework.beans.factory.annotation.Autowired
    private TransactionTemplate transactionTemplate;

    private final AtomicBoolean callbackCompleted = new AtomicBoolean();
    private final List<IntegrationProfileEvent> publishedEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        callbackCompleted.set(false);
        publishedEvents.clear();
        when(repository.existsActive(any(), any(), any())).thenReturn(false);
        when(repository.save(eq(TENANT_ID), any())).thenAnswer(invocation -> invocation.getArgument(1));
        doAnswer(invocation -> {
            assertThat(callbackCompleted).isTrue();
            publishedEvents.add(invocation.getArgument(0));
            return null;
        }).when(kafkaPublisher).publish(any());
    }

    @Test
    void publishesCreatedUpdatedAndDeactivatedEventsOnlyAfterTheirTransactionsCommit() {
        IntegrationProfile original = IntegrationProfile.create(PROFILE_ID, TENANT_ID, "orders", "erp",
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM);
        IntegrationProfile updated = original.update("catalog", "crm", SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, 0);
        when(repository.findById(TENANT_ID, PROFILE_ID)).thenReturn(original, updated);

        transactionTemplate.executeWithoutResult(status -> {
            service.create(TENANT_ID, new CreateIntegrationProfileCommand("orders", "erp", SyncDirection.INBOUND,
                    SourceOfTruth.PLATFORM));
            callbackCompleted.set(true);
        });
        callbackCompleted.set(false);
        transactionTemplate.executeWithoutResult(status -> {
            service.update(TENANT_ID, PROFILE_ID, new UpdateIntegrationProfileCommand("catalog", "crm",
                    SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, 0));
            callbackCompleted.set(true);
        });
        callbackCompleted.set(false);
        transactionTemplate.executeWithoutResult(status -> {
            service.deactivate(TENANT_ID, PROFILE_ID);
            callbackCompleted.set(true);
        });

        assertThat(publishedEvents).extracting(IntegrationProfileEvent::eventType)
                .containsExactly("IntegrationProfileCreated", "IntegrationProfileUpdated", "IntegrationProfileDeactivated");
        assertThat(publishedEvents).allSatisfy(event -> {
            assertThat(event.profileId()).isNotNull();
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.occurredAt()).isNotNull();
        });
        assertThat(publishedEvents.getLast().state().active()).isFalse();
    }

    @Test
    void suppressesKafkaPublicationWhenTheSurroundingTransactionRollsBack() {
        transactionTemplate.executeWithoutResult(status -> {
            service.create(TENANT_ID, new CreateIntegrationProfileCommand("orders", "erp", SyncDirection.INBOUND,
                    SourceOfTruth.PLATFORM));
            callbackCompleted.set(true);
            status.setRollbackOnly();
        });

        verify(kafkaPublisher, never()).publish(any());
    }

    @Test
    void suppressesKafkaPublicationWhenPersistenceFails() {
        when(repository.save(eq(TENANT_ID), any())).thenThrow(new IllegalStateException("persistence failed"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> service.create(TENANT_ID,
                new CreateIntegrationProfileCommand("orders", "erp", SyncDirection.INBOUND,
                        SourceOfTruth.PLATFORM))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("persistence failed");

        verify(kafkaPublisher, never()).publish(any());
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        IntegrationProfileRepository repository() {
            return mock(IntegrationProfileRepository.class);
        }

        @Bean
        IntegrationProfileEventPublisher kafkaPublisher() {
            return mock(IntegrationProfileEventPublisher.class);
        }

        @Bean
        IntegrationProfileService integrationProfileService(IntegrationProfileRepository repository,
                                                             ApplicationEventPublisher eventPublisher) {
            return new IntegrationProfileService(repository, eventPublisher);
        }

        @Bean
        IntegrationProfileEventListener integrationProfileEventListener(IntegrationProfileEventPublisher publisher) {
            return new IntegrationProfileEventListener(publisher);
        }

        @Bean
        TestTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        TransactionTemplate transactionTemplate(TestTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
