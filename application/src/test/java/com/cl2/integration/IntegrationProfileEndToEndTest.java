package com.cl2.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.cl2.integration.adapter.in.web.dto.IntegrationProfileResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class IntegrationProfileEndToEndTest extends IntegrationApplicationTest {

    private static final String BASE_PATH = "/api/v1/integration-profiles";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void isolatesProfilesAcrossTenantsAndRetainsDeactivatedProfiles() {
        UUID firstTenantId = UUID.randomUUID();
        UUID secondTenantId = UUID.randomUUID();

        IntegrationProfileResponse firstProfile = createProfile(firstTenantId, "orders", "erp");
        IntegrationProfileResponse secondProfile = createProfile(secondTenantId, "catalog", "crm");

        ResponseEntity<IntegrationProfileResponse[]> firstTenantProfiles = exchange(
                firstTenantId, HttpMethod.GET, BASE_PATH, null, IntegrationProfileResponse[].class);
        ResponseEntity<IntegrationProfileResponse[]> secondTenantProfiles = exchange(
                secondTenantId, HttpMethod.GET, BASE_PATH, null, IntegrationProfileResponse[].class);

        assertThat(firstTenantProfiles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstTenantProfiles.getBody()).extracting(IntegrationProfileResponse::id)
                .containsExactly(firstProfile.id());
        assertThat(secondTenantProfiles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondTenantProfiles.getBody()).extracting(IntegrationProfileResponse::id)
                .containsExactly(secondProfile.id());

        assertThat(exchange(secondTenantId, HttpMethod.GET, BASE_PATH + "/" + firstProfile.id(), null,
                IntegrationProfileResponse.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(secondTenantId, HttpMethod.PUT, BASE_PATH + "/" + firstProfile.id(), updatePayload(),
                IntegrationProfileResponse.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(secondTenantId, HttpMethod.DELETE, BASE_PATH + "/" + firstProfile.id(), null,
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(firstTenantId, HttpMethod.GET, BASE_PATH + "/" + firstProfile.id(), null,
                IntegrationProfileResponse.class).getBody()).isNotNull().satisfies(profile -> {
                    assertThat(profile.businessDomain()).isEqualTo("orders");
                    assertThat(profile.externalSource()).isEqualTo("erp");
                    assertThat(profile.active()).isTrue();
                    assertThat(profile.configuration()).isNull();
                });

        assertThat(exchange(firstTenantId, HttpMethod.DELETE, BASE_PATH + "/" + firstProfile.id(), null,
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<IntegrationProfileResponse[]> includingInactive = exchange(firstTenantId, HttpMethod.GET,
                BASE_PATH + "?activeOnly=false", null, IntegrationProfileResponse[].class);
        assertThat(includingInactive.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(includingInactive.getBody()).singleElement().satisfies(profile -> {
            assertThat(profile.id()).isEqualTo(firstProfile.id());
            assertThat(profile.active()).isFalse();
        });
    }

    @Test
    void supportsConfiguredIntegrationProfileLifecycle() {
        UUID tenantId = UUID.randomUUID();

        String createPayload = """
                {"businessDomain":"orders","externalSource":"erp",
                 "syncDirection":"INBOUND","sourceOfTruth":"PLATFORM",
                 "protocol":"REST","connector":"sigo",
                 "adapter":"sigo-vehicle-http","endpoint":"https://sigo.test/api",
                 "credentialRef":"secret/sigo/orders",
                 "mapping":{"vin":"vehicle.vin"},
                 "retryPolicy":{"maxAttempts":3,"initialBackoffMs":100},
                 "rateLimitPolicy":{"requestsPerSecond":10}}
                """;

        ResponseEntity<IntegrationProfileResponse> createdResponse = exchange(
                tenantId, HttpMethod.POST, BASE_PATH, createPayload, IntegrationProfileResponse.class);

        assertThat(createdResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        IntegrationProfileResponse created = createdResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.configuration()).isNotNull();
        assertThat(created.configuration().protocol().name()).isEqualTo("REST");
        assertThat(created.configuration().credentialRef()).isEqualTo("secret/sigo/orders");
        assertThat(created.configuration().mapping().get("vin").asText()).isEqualTo("vehicle.vin");

        // Conflict on wrong expectedVersion
        String staleUpdatePayload = """
                {"businessDomain":"orders","externalSource":"erp",
                 "syncDirection":"OUTBOUND","sourceOfTruth":"PLATFORM",
                 "expectedVersion":99}
                """;
        ResponseEntity<String> conflictResponse = exchange(
                tenantId, HttpMethod.PUT, BASE_PATH + "/" + created.id(), staleUpdatePayload, String.class);
        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Successful update with new configuration
        String updatePayload = """
                {"businessDomain":"orders","externalSource":"erp",
                 "syncDirection":"OUTBOUND","sourceOfTruth":"PLATFORM",
                 "protocol":"KAFKA","connector":"kafka-conn",
                 "adapter":"kafka-adapter","endpoint":"localhost:9092",
                 "credentialRef":"secret/kafka",
                 "expectedVersion":0}
                """;
        ResponseEntity<IntegrationProfileResponse> updatedResponse = exchange(
                tenantId, HttpMethod.PUT, BASE_PATH + "/" + created.id(), updatePayload, IntegrationProfileResponse.class);
        assertThat(updatedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        IntegrationProfileResponse updated = updatedResponse.getBody();
        assertThat(updated).isNotNull();
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.configuration().protocol().name()).isEqualTo("KAFKA");
        assertThat(updated.configuration().credentialRef()).isEqualTo("secret/kafka");
    }

    private IntegrationProfileResponse createProfile(UUID tenantId, String businessDomain, String externalSource) {
        ResponseEntity<IntegrationProfileResponse> response = exchange(tenantId, HttpMethod.POST, BASE_PATH, """
                {"businessDomain":"%s","externalSource":"%s","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}
                """.formatted(businessDomain, externalSource), IntegrationProfileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private String updatePayload() {
        return """
                {"businessDomain":"orders","externalSource":"erp","syncDirection":"OUTBOUND","sourceOfTruth":"EXTERNAL","expectedVersion":0}
                """;
    }

    private <T> ResponseEntity<T> exchange(
            UUID tenantId, HttpMethod method, String path, String body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", tenantId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), responseType);
    }
}
