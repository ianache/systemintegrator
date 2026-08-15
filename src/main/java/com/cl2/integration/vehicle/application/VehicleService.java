package com.cl2.integration.vehicle.application;

import com.cl2.integration.vehicle.domain.Vehicle;
import com.cl2.integration.vehicle.domain.VehicleRepository;
import com.cl2.integration.integration.outbox.OutboxEvent;
import com.cl2.integration.integration.outbox.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class VehicleService {
    private final VehicleRepository vehicles;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public VehicleService(VehicleRepository vehicles, OutboxRepository outbox, ObjectMapper objectMapper) {
        this.vehicles = vehicles;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Vehicle create(UUID tenantId, String vin, String brandCode, String modelCode, int modelYear) {
        if (vehicles.existsByVin(tenantId, vin)) {
            throw new IllegalArgumentException("A vehicle with this VIN already exists for the tenant");
        }
        Vehicle vehicle = Vehicle.create(UUID.randomUUID(), tenantId, vin, brandCode, modelCode, modelYear);
        Vehicle saved = vehicles.save(vehicle);
        try {
            outbox.save(OutboxEvent.pending(saved.tenantId(), saved.id(), "Vehicle", "vehicle.created",
                    objectMapper.writeValueAsString(VehicleEvent.created(saved))));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize vehicle event", exception);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public Vehicle get(UUID tenantId, UUID id) { return vehicles.findById(tenantId, id); }

    @Transactional(readOnly = true)
    public List<Vehicle> list(UUID tenantId, boolean activeOnly) { return vehicles.findAll(tenantId, activeOnly); }
}
