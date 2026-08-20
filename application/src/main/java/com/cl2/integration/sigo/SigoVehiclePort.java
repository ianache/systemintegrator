package com.cl2.integration.sigo;

import com.cl2.integration.vehicle.domain.Vehicle;

public interface SigoVehiclePort {
    void send(Vehicle vehicle, String endpoint);
}
