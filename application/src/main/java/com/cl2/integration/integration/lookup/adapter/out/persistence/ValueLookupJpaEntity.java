package com.cl2.integration.integration.lookup.adapter.out.persistence;

import com.cl2.integration.integration.lookup.domain.ValueLookup;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "integration_value_lookup")
class ValueLookupJpaEntity {

    @Id
    @JdbcTypeCode(Types.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @JdbcTypeCode(Types.BINARY)
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "external_source", nullable = false, length = 100)
    private String externalSource;

    @Column(name = "catalog_code", nullable = false, length = 100)
    private String catalogCode;

    @Column(name = "source_value", nullable = false, length = 255)
    private String sourceValue;

    @Column(name = "target_value", nullable = false, length = 255)
    private String targetValue;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    private Instant updatedAt;

    protected ValueLookupJpaEntity() {
    }

    private ValueLookupJpaEntity(ValueLookup lookup) {
        this.id = lookup.id();
        this.tenantId = lookup.tenantId();
        this.externalSource = lookup.externalSource();
        this.catalogCode = lookup.catalogCode();
        this.sourceValue = lookup.sourceValue();
        this.targetValue = lookup.targetValue();
        this.description = lookup.description();
        this.active = lookup.active();
        this.createdAt = toMysqlTimestamp(lookup.createdAt());
        this.updatedAt = toMysqlTimestamp(lookup.updatedAt());
    }

    static ValueLookupJpaEntity from(ValueLookup lookup) {
        return new ValueLookupJpaEntity(lookup);
    }

    ValueLookup toDomain() {
        return ValueLookup.rehydrate(
                id,
                tenantId,
                externalSource,
                catalogCode,
                sourceValue,
                targetValue,
                description,
                active,
                createdAt,
                updatedAt
        );
    }

    private static Instant toMysqlTimestamp(Instant timestamp) {
        return timestamp != null ? timestamp.truncatedTo(ChronoUnit.MICROS) : Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
