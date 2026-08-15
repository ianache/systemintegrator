package com.cl2.integration.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
class GatewayApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() { }

    @Test
    void defaultContextDoesNotConfigureAnExternalResourceServerIssuer() {
        assertNull(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
    }
}
