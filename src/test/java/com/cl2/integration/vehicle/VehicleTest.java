package com.cl2.integration.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import com.cl2.integration.vehicle.domain.Vehicle;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleTest {

    @Test
    void createsAnActiveCanonicalVehicleWithTenantOwnership() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        Vehicle vehicle = Vehicle.create(id, tenantId, "VIN-001", "TOYOTA", "COROLLA", 2025);

        assertThat(vehicle.id()).isEqualTo(id);
        assertThat(vehicle.tenantId()).isEqualTo(tenantId);
        assertThat(vehicle.vin()).isEqualTo("VIN-001");
        assertThat(vehicle.brandCode()).isEqualTo("TOYOTA");
        assertThat(vehicle.modelCode()).isEqualTo("COROLLA");
        assertThat(vehicle.modelYear()).isEqualTo(2025);
        assertThat(vehicle.active()).isTrue();
        assertThat(vehicle.version()).isZero();
    }
}
