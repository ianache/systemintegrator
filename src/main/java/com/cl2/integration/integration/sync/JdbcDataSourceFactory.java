package com.cl2.integration.integration.sync;

import com.cl2.integration.integration.security.ResolvedSecret;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.stereotype.Component;

@Component
public class JdbcDataSourceFactory {

    public HikariDataSource create(String endpoint, ResolvedSecret secret) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(endpoint)
                .username(secret.username())
                .password(secret.password())
                .build();
    }
}