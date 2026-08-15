package com.cl2.integration.integration.profile;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class IntegrationProfileEventListener {

    private final IntegrationProfileEventPublisher publisher;

    public IntegrationProfileEventListener(IntegrationProfileEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(IntegrationProfileEvent event) {
        publisher.publish(event);
    }
}
