package com.cl2.integration.vehicle.domain;

import java.util.List;
import java.util.UUID;

public interface VehicleRepository {
    Vehicle save(Vehicle vehicle);
    Vehicle findById(UUID tenantId, UUID id);
    List<Vehicle> findAll(UUID tenantId, boolean activeOnly);
    boolean existsByVin(UUID tenantId, String vin);
}
