package com.cl2.integration.vehicle.application;

import com.cl2.integration.vehicle.domain.Vehicle;
import java.time.Instant;
import java.util.UUID;

public record VehicleEvent(UUID eventId, UUID tenantId, UUID vehicleId, String eventType, VehicleSnapshot vehicle,
                           Instant occurredAt) {
    public static VehicleEvent created(Vehicle vehicle) {
        return new VehicleEvent(UUID.randomUUID(), vehicle.tenantId(), vehicle.id(), "vehicle.created",
                VehicleSnapshot.from(vehicle), vehicle.updatedAt());
    }

    public record VehicleSnapshot(String vin, String brandCode, String modelCode, int modelYear, boolean active) {
        static VehicleSnapshot from(Vehicle vehicle) {
            return new VehicleSnapshot(vehicle.vin(), vehicle.brandCode(), vehicle.modelCode(), vehicle.modelYear(), vehicle.active());
        }
    }
}
