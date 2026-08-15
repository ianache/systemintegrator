package com.cl2.integration.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "KEYCLOAK_ISSUER_URI=https://issuer.example.test/realms/integration")
class GatewayApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() { }

    @Test
    void exposesKeycloakIssuerUriFromEnvironment() {
        assertEquals(
                "https://issuer.example.test/realms/integration",
                environment.getProperty("keycloak.issuer-uri"));
    }
}
