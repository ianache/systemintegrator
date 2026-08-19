package com.cl2.integration.integration.sync;

import com.cl2.integration.integration.security.ResolvedSecret;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class JdbcDataSourceFactoryTest {

    @Autowired
    private JdbcDataSourceFactory factory;

    @Test
    void buildsAWorkingDataSourceFromEndpointAndSecret() {
        ResolvedSecret secret = ResolvedSecret.basic("secret/test", "integration", "integration");

        try (HikariDataSource dataSource = factory.create(
                "jdbc:mysql://localhost:3306/integration?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
                secret)) {
            Integer result = new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
            assertThat(result).isEqualTo(1);
        }
    }
}