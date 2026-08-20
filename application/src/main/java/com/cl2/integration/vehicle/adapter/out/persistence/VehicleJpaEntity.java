package com.cl2.integration.vehicle.adapter.out.persistence;

import com.cl2.integration.vehicle.domain.Vehicle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "vehicle")
class VehicleJpaEntity {
    @Id @JdbcTypeCode(Types.BINARY) @Column(columnDefinition = "BINARY(16)") private UUID id;
    @JdbcTypeCode(Types.BINARY) @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)") private UUID tenantId;
    @Column(nullable = false, length = 100) private String vin;
    @Column(name = "brand_code", nullable = false, length = 100) private String brandCode;
    @Column(name = "model_code", nullable = false, length = 100) private String modelCode;
    @Column(name = "model_year", nullable = false) private int modelYear;
    @Column(nullable = false) private boolean active;
    @Version @Column(nullable = false) private long version;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP(6)") private Instant createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP(6)") private Instant updatedAt;

    protected VehicleJpaEntity() { }

    private VehicleJpaEntity(Vehicle vehicle) {
        id = vehicle.id(); tenantId = vehicle.tenantId(); vin = vehicle.vin(); brandCode = vehicle.brandCode();
        modelCode = vehicle.modelCode(); modelYear = vehicle.modelYear(); active = vehicle.active(); version = vehicle.version();
        createdAt = vehicle.createdAt().truncatedTo(ChronoUnit.MICROS); updatedAt = vehicle.updatedAt().truncatedTo(ChronoUnit.MICROS);
    }

    static VehicleJpaEntity from(Vehicle vehicle) { return new VehicleJpaEntity(vehicle); }

    Vehicle toDomain() { return Vehicle.rehydrate(id, tenantId, vin, brandCode, modelCode, modelYear, active, createdAt, updatedAt, version); }
}
