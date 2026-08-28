package com.cl2.integration.integration.monitor;

import com.cl2.integration.integration.inbox.InboxJpaEntity;
import com.cl2.integration.integration.inbox.SpringDataInboxRepository;
import com.cl2.integration.integration.outbound.OutboundEventDispatcher;
import com.cl2.integration.integration.outbox.OutboxJpaEntity;
import com.cl2.integration.integration.outbox.SpringDataOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MessageMonitorService {

    public static final String DIRECTION_INBOUND = "INBOUND";
    public static final String DIRECTION_OUTBOUND = "OUTBOUND";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_DLQ = "DLQ";
    private static final String STATUS_PROCESSED = "PROCESSED";

    private static final int WINDOW_SIZE = 200;

    private final SpringDataInboxRepository inboxRepository;
    private final SpringDataOutboxRepository outboxRepository;
    private final OutboundEventDispatcher outboundEventDispatcher;

    public MessageMonitorService(
            SpringDataInboxRepository inboxRepository,
            SpringDataOutboxRepository outboxRepository,
            OutboundEventDispatcher outboundEventDispatcher) {
        this.inboxRepository = inboxRepository;
        this.outboxRepository = outboxRepository;
        this.outboundEventDispatcher = outboundEventDispatcher;
    }

    public List<MessageSummary> list(UUID tenantId, String statusFilter) {
        PageRequest window = PageRequest.of(0, WINDOW_SIZE, Sort.unsorted());

        Stream<MessageSummary> inbound = inboxRepository.findByTenantId(tenantId, window).stream()
                .map(MessageMonitorService::toSummary);
        Stream<MessageSummary> outbound = outboxRepository.findByTenantId(tenantId, window).stream()
                .map(MessageMonitorService::toSummary);

        return Stream.concat(inbound, outbound)
                .filter(m -> statusFilter == null || "ALL".equals(statusFilter) || statusFilter.equals(m.status()))
                .sorted(Comparator.comparing(MessageSummary::timestamp).reversed())
                .limit(WINDOW_SIZE)
                .collect(Collectors.toList());
    }

    public MessageDetail find(UUID tenantId, String direction, UUID id) {
        if (DIRECTION_INBOUND.equals(direction)) {
            InboxJpaEntity entity = inboxRepository.findByEventIdAndTenantId(id, tenantId)
                    .orElseThrow(() -> new MessageNotFoundException("Message " + id + " not found"));
            return toDetail(entity);
        }
        if (DIRECTION_OUTBOUND.equals(direction)) {
            OutboxJpaEntity entity = outboxRepository.findByIdAndTenantId(id, tenantId)
                    .orElseThrow(() -> new MessageNotFoundException("Message " + id + " not found"));
            return toDetail(entity);
        }
        throw new IllegalArgumentException("Unsupported direction: " + direction);
    }

    @Transactional
    public MessageDetail retry(UUID tenantId, String direction, UUID id) {
        if (DIRECTION_INBOUND.equals(direction)) {
            InboxJpaEntity entity = inboxRepository.findByEventIdAndTenantId(id, tenantId)
                    .orElseThrow(() -> new MessageNotFoundException("Message " + id + " not found"));
            try {
                outboundEventDispatcher.dispatch(entity.getEventId(), entity.getTenantId(), entity.getEventType(), entity.getPayload(), null);
                entity.markProcessed(Instant.now());
            } catch (Exception ex) {
                entity.markDeadLetter(ex.getMessage());
            }
            inboxRepository.save(entity);
            return toDetail(entity);
        }
        if (DIRECTION_OUTBOUND.equals(direction)) {
            OutboxJpaEntity entity = outboxRepository.findByIdAndTenantId(id, tenantId)
                    .orElseThrow(() -> new MessageNotFoundException("Message " + id + " not found"));
            // Reset to PENDING with an immediate availableAt: the existing
            // OutboxRelayScheduler picks it up on its next poll, reusing the
            // same publish path as any other pending message.
            entity.retryNow(Instant.now());
            outboxRepository.save(entity);
            return toDetail(entity);
        }
        throw new IllegalArgumentException("Unsupported direction: " + direction);
    }

    @Transactional
    public MessageDetail moveToDlq(UUID tenantId, String direction, UUID id) {
        if (DIRECTION_INBOUND.equals(direction)) {
            InboxJpaEntity entity = inboxRepository.findByEventIdAndTenantId(id, tenantId)
                    .orElseThrow(() -> new MessageNotFoundException("Message " + id + " not found"));
            entity.markDeadLetter("Movido manualmente a DLQ por el operador");
            inboxRepository.save(entity);
            return toDetail(entity);
        }
        if (DIRECTION_OUTBOUND.equals(direction)) {
            OutboxJpaEntity entity = outboxRepository.findByIdAndTenantId(id, tenantId)
                    .orElseThrow(() -> new MessageNotFoundException("Message " + id + " not found"));
            entity.markFailed("Movido manualmente a DLQ por el operador", null, true);
            outboxRepository.save(entity);
            return toDetail(entity);
        }
        throw new IllegalArgumentException("Unsupported direction: " + direction);
    }

    private static MessageSummary toSummary(InboxJpaEntity entity) {
        return new MessageSummary(
                entity.getEventId(),
                DIRECTION_INBOUND,
                entity.getEventType(),
                domainOf(entity.getEventType()),
                normalizeInboxStatus(entity.getStatus(), entity.getAttempts()),
                entity.getAttempts(),
                entity.getLastError(),
                entity.getReceivedAt()
        );
    }

    private static MessageDetail toDetail(InboxJpaEntity entity) {
        return new MessageDetail(
                entity.getEventId(),
                DIRECTION_INBOUND,
                entity.getEventType(),
                domainOf(entity.getEventType()),
                normalizeInboxStatus(entity.getStatus(), entity.getAttempts()),
                entity.getAttempts(),
                entity.getLastError(),
                entity.getReceivedAt(),
                entity.getPayload()
        );
    }

    private static MessageSummary toSummary(OutboxJpaEntity entity) {
        return new MessageSummary(
                entity.getId(),
                DIRECTION_OUTBOUND,
                entity.getEventType(),
                domainOf(entity.getEventType()),
                normalizeOutboxStatus(entity.getStatus(), entity.getAttempts()),
                entity.getAttempts(),
                entity.getLastError(),
                entity.getCreatedAt()
        );
    }

    private static MessageDetail toDetail(OutboxJpaEntity entity) {
        return new MessageDetail(
                entity.getId(),
                DIRECTION_OUTBOUND,
                entity.getEventType(),
                domainOf(entity.getEventType()),
                normalizeOutboxStatus(entity.getStatus(), entity.getAttempts()),
                entity.getAttempts(),
                entity.getLastError(),
                entity.getCreatedAt(),
                entity.getPayload()
        );
    }

    private static String domainOf(String eventType) {
        if (eventType == null) {
            return "";
        }
        int dot = eventType.indexOf('.');
        return dot > 0 ? eventType.substring(0, dot) : eventType;
    }

    private static String normalizeInboxStatus(String rawStatus, int attempts) {
        return switch (rawStatus) {
            case "RECEIVED" -> attempts > 0 ? STATUS_ERROR : STATUS_PENDING;
            case "PROCESSED" -> STATUS_PROCESSED;
            case "DEAD_LETTER" -> STATUS_DLQ;
            default -> rawStatus;
        };
    }

    private static String normalizeOutboxStatus(String rawStatus, int attempts) {
        return switch (rawStatus) {
            case "PENDING" -> attempts > 0 ? STATUS_ERROR : STATUS_PENDING;
            case "PUBLISHED" -> STATUS_PROCESSED;
            case "FAILED" -> STATUS_DLQ;
            default -> rawStatus;
        };
    }
}
