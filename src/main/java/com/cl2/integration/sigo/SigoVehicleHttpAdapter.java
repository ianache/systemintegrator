package com.cl2.integration.sigo;

import com.cl2.integration.vehicle.domain.Vehicle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SigoVehicleHttpAdapter implements SigoVehiclePort {
    private final RestClient restClient;
    public SigoVehicleHttpAdapter(RestClient.Builder builder) { this.restClient = builder.build(); }
    @Override public void send(Vehicle vehicle, String endpoint) {
        restClient.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON).body(vehicle).retrieve().toBodilessEntity();
    }
}
