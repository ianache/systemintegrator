package com.cl2.integration.integration.inbox;

import org.springframework.data.repository.Repository;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataInboxRepository extends Repository<InboxJpaEntity, UUID> {
    InboxJpaEntity save(InboxJpaEntity entity);
    Optional<InboxJpaEntity> findById(UUID eventId);
    Optional<InboxJpaEntity> findByEventIdAndTenantId(UUID eventId, UUID tenantId);
    boolean existsByEventIdAndTenantId(UUID eventId, UUID tenantId);
    boolean existsById(UUID eventId);

    @org.springframework.data.jpa.repository.Query("SELECT e FROM InboxJpaEntity e WHERE e.tenantId = :tenantId AND e.status = :status ORDER BY e.receivedAt ASC")
    java.util.List<InboxJpaEntity> findByTenantIdAndStatus(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, @org.springframework.data.repository.query.Param("status") String status);

    @org.springframework.data.jpa.repository.Query("SELECT e FROM InboxJpaEntity e WHERE e.status = :status ORDER BY e.receivedAt ASC")
    java.util.List<InboxJpaEntity> findByStatus(@org.springframework.data.repository.query.Param("status") String status);
}
