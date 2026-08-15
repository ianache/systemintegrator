package com.cl2.integration.e2e;

import com.cl2.integration.adapter.in.web.dto.IntegrationProfileResponse;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class ApiClient {

    private static final String BASE_PATH = "/api/v1/integration-profiles";

    private final TestRestTemplate restTemplate;
    private final String baseUrl;

    ApiClient(TestRestTemplate restTemplate, int port) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate must not be null");
        this.baseUrl = "http://localhost:" + port;
    }

    ResponseEntity<IntegrationProfileResponse> create(UUID tenantId, String domain, String source) {
        return exchange(tenantId, HttpMethod.POST, BASE_PATH, IntegrationProfilePayloads.create(domain, source),
                IntegrationProfileResponse.class);
    }

    ResponseEntity<IntegrationProfileResponse[]> list(UUID tenantId, boolean activeOnly) {
        return exchange(tenantId, HttpMethod.GET, BASE_PATH + "?activeOnly=" + activeOnly, null,
                IntegrationProfileResponse[].class);
    }

    ResponseEntity<IntegrationProfileResponse> get(UUID tenantId, UUID profileId) {
        return exchange(tenantId, HttpMethod.GET, profilePath(profileId), null, IntegrationProfileResponse.class);
    }

    ResponseEntity<IntegrationProfileResponse> update(
            UUID tenantId, UUID profileId, String domain, String source, long expectedVersion) {
        return exchange(tenantId, HttpMethod.PUT, profilePath(profileId),
                IntegrationProfilePayloads.update(domain, source, expectedVersion), IntegrationProfileResponse.class);
    }

    ResponseEntity<Void> deactivate(UUID tenantId, UUID profileId) {
        return exchange(tenantId, HttpMethod.DELETE, profilePath(profileId), null, Void.class);
    }

    private String profilePath(UUID profileId) {
        return BASE_PATH + "/" + Objects.requireNonNull(profileId, "profileId must not be null");
    }

    private <T> ResponseEntity<T> exchange(
            UUID tenantId, HttpMethod method, String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", Objects.requireNonNull(tenantId, "tenantId must not be null").toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), responseType);
    }
}
