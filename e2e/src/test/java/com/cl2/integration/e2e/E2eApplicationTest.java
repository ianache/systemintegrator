package com.cl2.integration.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cl2.integration.IntegrationApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("e2e")
@SpringBootTest(classes = IntegrationApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E2eApplicationTest {

    protected static final String EVENTS_TOPIC = "integration-profile.events";

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void startsTheApplicationWithMySqlAndKafkaOnARandomHttpPort() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", UUID.randomUUID().toString());

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/integration-profiles?activeOnly=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
