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

    @Query(value = "SELECT * FROM integration_outbox WHERE status = 'PENDING' AND available_at <= :now ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxJpaEntity> findPendingForPublishing(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = "UPDATE integration_outbox SET status = 'CANCELLED', last_error = :errorReason WHERE tenant_id = :tenantId AND topic = :topic AND status = 'PENDING'", nativeQuery = true)
    int cancelPendingByTenantAndTopic(@Param("tenantId") UUID tenantId, @Param("topic") String topic, @Param("errorReason") String errorReason);
}
