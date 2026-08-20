package com.cl2.integration.vehicle.adapter.out.persistence;

import com.cl2.integration.vehicle.domain.Vehicle;
import com.cl2.integration.vehicle.domain.VehicleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class VehiclePersistenceAdapter implements VehicleRepository {
    private final SpringDataVehicleRepository repository;
    VehiclePersistenceAdapter(SpringDataVehicleRepository repository) { this.repository = repository; }
    @Override public Vehicle save(Vehicle vehicle) { return repository.save(VehicleJpaEntity.from(vehicle)).toDomain(); }
    @Override public Vehicle findById(UUID tenantId, UUID id) {
        return repository.findByTenantIdAndId(tenantId, id).map(VehicleJpaEntity::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("Vehicle was not found"));
    }
    @Override public List<Vehicle> findAll(UUID tenantId, boolean activeOnly) {
        var entities = activeOnly ? repository.findAllByTenantIdAndActiveTrueOrderByCreatedAtDesc(tenantId)
                : repository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        return entities.stream().map(VehicleJpaEntity::toDomain).toList();
    }
    @Override public boolean existsByVin(UUID tenantId, String vin) { return repository.existsByTenantIdAndVin(tenantId, vin); }
}
