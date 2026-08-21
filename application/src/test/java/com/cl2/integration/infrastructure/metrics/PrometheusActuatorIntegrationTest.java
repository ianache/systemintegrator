package com.cl2.integration.infrastructure.metrics;

import com.cl2.integration.IntegrationApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusActuatorIntegrationTest extends IntegrationApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IntegrationMetrics integrationMetrics;

    @Test
    @DisplayName("Should expose /actuator/prometheus endpoint containing integration metrics")
    void shouldExposePrometheusEndpoint() {
        integrationMetrics.recordSyncRun("tenant-1", "units", "sigo", "SUCCESS", 0.5, 10);
        integrationMetrics.recordOutboundHttpRequest("tenant-1", "test-connector", 200, 0.1);

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("jvm_memory_used_bytes")
                .contains("integration_sync_duration_seconds")
                .contains("integration_outbound_http_requests_total");
    }
}

