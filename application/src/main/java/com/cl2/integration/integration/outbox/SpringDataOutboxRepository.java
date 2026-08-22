package com.cl2.integration.integration.outbox;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataOutboxRepository extends Repository<OutboxJpaEntity, UUID> {
    OutboxJpaEntity save(OutboxJpaEntity entity);
    Optional<OutboxJpaEntity> findById(UUID id);

    @Query("SELECT e FROM OutboxJpaEntity e WHERE e.tenantId = :tenantId AND e.aggregateId = :aggregateId ORDER BY e.createdAt DESC")
    List<OutboxJpaEntity> findByTenantIdAndAggregateIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId, @Param("aggregateId") UUID aggregateId);

    default Optional<OutboxJpaEntity> findLatestByTenantIdAndAggregateId(UUID tenantId, UUID aggregateId) {
        List<OutboxJpaEntity> list = findByTenantIdAndAggregateIdOrderByCreatedAtDesc(tenantId, aggregateId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Query(value = "SELECT * FROM integration_outbox WHERE status = 'PENDING' AND available_at <= :now ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxJpaEntity> findPendingForPublishing(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE OutboxJpaEntity e SET e.status = 'CANCELLED', e.lastError = :errorReason WHERE e.tenantId = :tenantId AND e.topic = :topic AND e.status = 'PENDING'")
    int cancelPendingByTenantAndTopic(@Param("tenantId") UUID tenantId, @Param("topic") String topic, @Param("errorReason") String errorReason);
}
