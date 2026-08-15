package com.cl2.integration.vehicle.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface SpringDataVehicleRepository extends Repository<VehicleJpaEntity, UUID> {
    Optional<VehicleJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);
    List<VehicleJpaEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<VehicleJpaEntity> findAllByTenantIdAndActiveTrueOrderByCreatedAtDesc(UUID tenantId);
    boolean existsByTenantIdAndVin(UUID tenantId, String vin);
    VehicleJpaEntity save(VehicleJpaEntity entity);
}
