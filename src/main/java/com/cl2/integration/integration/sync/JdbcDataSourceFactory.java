package com.cl2.integration.integration.sync;

import com.cl2.integration.integration.security.ResolvedSecret;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.stereotype.Component;

@Component
public class JdbcDataSourceFactory {

    public HikariDataSource create(String endpoint, ResolvedSecret secret) {
        var builder = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(endpoint)
                .username(secret.username())
                .password(secret.password());

        if (endpoint != null && endpoint.startsWith("jdbc:sap:")) {
            builder.driverClassName("com.sap.db.jdbc.Driver");
        } else if (endpoint != null && endpoint.startsWith("jdbc:mysql:")) {
            builder.driverClassName("com.mysql.cj.jdbc.Driver");
        }

        HikariDataSource dataSource = builder.build();
        dataSource.setConnectionTimeout(15000);
        return dataSource;
    }
}