package com.cl2.integration.integration.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KafkaOutboxPublisherTest {

    @SuppressWarnings("unchecked")
    @Test
    void publishAttachesProvenanceHeadersIncludingBusinessDomain() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate);

        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(
                id,
                tenantId,
                aggregateId,
                "Customer",
                "customer.created",
                "integration.customers.events",
                "{\"name\":\"John\"}",
                "PENDING",
                0,
                Instant.now(),
                null,
                null,
                Instant.now()
        );
        OutboxJpaEntity entity = OutboxJpaEntity.from(event);

        publisher.publish(entity);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("integration.customers.events");
        assertThat(record.key()).isEqualTo(id.toString());
        assertThat(record.value()).isEqualTo("{\"name\":\"John\"}");

        assertThat(getHeader(record, "X-Tenant-ID")).isEqualTo(tenantId.toString());
        assertThat(getHeader(record, "X-Event-Type")).isEqualTo("customer.created");
        assertThat(getHeader(record, "X-Aggregate-ID")).isEqualTo(aggregateId.toString());
        assertThat(getHeader(record, "X-Business-Domain")).isEqualTo("Customer");
        assertThat(getHeader(record, "X-External-Source")).isNull();
        assertThat(getHeader(record, "X-Batch-Mode")).isNull();
        assertThat(getHeader(record, "X-Batch-Size")).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishBatchEventAttachesBatchHeadersWithPayloadArraySize() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate);

        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Customer",
                "customer.batch.upserted",
                "integration.customers.batch.events",
                "[{\"id\":\"customer-1\"},{\"id\":\"customer-2\"}]",
                "PENDING",
                0,
                Instant.now(),
                null,
                null,
                Instant.now()
        );

        publisher.publish(OutboxJpaEntity.from(event));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(getHeader(record, "X-Batch-Mode")).isEqualTo("true");
        assertThat(getHeader(record, "X-Batch-Size")).isEqualTo("2");
        assertThat(getHeader(record, "X-Tenant-ID")).isEqualTo(event.tenantId().toString());
        assertThat(getHeader(record, "X-Event-Type")).isEqualTo("customer.batch.upserted");
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishPropagatesPersistedExternalSourceWithoutChangingThePayload() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate);
        String payload = "[{\"id\":\"customer-1\"}]";
        OutboxEvent event = OutboxEvent.pending(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Customer",
                "customer.batch.upserted",
                "integration.customers.batch.events",
                payload,
                "sap-hana");

        publisher.publish(OutboxJpaEntity.from(event));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(getHeader(captor.getValue(), "X-External-Source")).isEqualTo("sap-hana");
        assertThat(captor.getValue().value()).isEqualTo(payload);
    }

    @Test
    void publishBatchEventWithNonArrayPayloadFailsBeforeSending() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate);

        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Customer",
                "customer.batch.upserted",
                "integration.customers.batch.events",
                "{\"id\":\"customer-1\"}",
                "PENDING",
                0,
                Instant.now(),
                null,
                null,
                Instant.now()
        );

        assertThatThrownBy(() -> publisher.publish(OutboxJpaEntity.from(event)))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(kafkaTemplate);
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishWithoutAggregateTypeOmitsBusinessDomainHeader() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate);

        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(
                id,
                tenantId,
                aggregateId,
                null,
                "customer.created",
                "integration.events",
                "{\"name\":\"John\"}",
                "PENDING",
                0,
                Instant.now(),
                null,
                null,
                Instant.now()
        );
        OutboxJpaEntity entity = OutboxJpaEntity.from(event);

        publisher.publish(entity);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(getHeader(record, "X-Business-Domain")).isNull();
    }

    private String getHeader(ProducerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
