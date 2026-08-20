package com.cl2.integration.vehicle.adapter.in.web;

import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.vehicle.application.VehicleService;
import com.cl2.integration.vehicle.domain.Vehicle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {
    private final VehicleService service;
    public VehicleController(VehicleService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse create(@Valid @RequestBody CreateVehicleRequest request) {
        return VehicleResponse.from(service.create(TenantContext.requireTenantId(), request.vin(), request.brandCode(),
                request.modelCode(), request.modelYear()));
    }

    @GetMapping
    public List<VehicleResponse> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.list(TenantContext.requireTenantId(), activeOnly).stream().map(VehicleResponse::from).toList();
    }

    @GetMapping("/{vehicleId}")
    public VehicleResponse get(@PathVariable UUID vehicleId) {
        return VehicleResponse.from(service.get(TenantContext.requireTenantId(), vehicleId));
    }

    public record CreateVehicleRequest(@NotBlank String vin, @NotBlank String brandCode, @NotBlank String modelCode,
                                       @Min(1886) @Max(3000) int modelYear) { }
    public record VehicleResponse(UUID id, UUID tenantId, String vin, String brandCode, String modelCode, int modelYear,
                                  boolean active, long version) {
        static VehicleResponse from(Vehicle v) { return new VehicleResponse(v.id(), v.tenantId(), v.vin(), v.brandCode(), v.modelCode(), v.modelYear(), v.active(), v.version()); }
    }
}
