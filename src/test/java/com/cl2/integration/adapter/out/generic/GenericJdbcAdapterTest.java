package com.cl2.integration.adapter.out.generic;

import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.adapter.out.generic.security.InvalidSqlExtractionException;
import com.cl2.integration.adapter.out.generic.security.SqlSecurityValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GenericJdbcAdapterTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private SqlSecurityValidator validator;
    private GenericJdbcAdapter adapter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbcTemplate = new NamedParameterJdbcTemplate(ds);
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS CUSTOMERS");
        jdbcTemplate.getJdbcTemplate().execute("CREATE TABLE CUSTOMERS (ID VARCHAR(50), NAME VARCHAR(50), UPDATED_AT TIMESTAMP)");

        validator = new SqlSecurityValidator();
        adapter = new GenericJdbcAdapter(validator);
    }

    @Test
    void shouldExtractDataViaJdbcTemplate() {
        jdbcTemplate.getJdbcTemplate().execute("INSERT INTO CUSTOMERS VALUES ('C1', 'ACME Corp', NOW())");

        ExtractionConfig config = new ExtractionConfig(
                "SELECT ID AS customerId, NAME AS legalName FROM CUSTOMERS WHERE UPDATED_AT >= :lastSyncWithBuffer",
                "lastSyncWithBuffer", "customerId", 500, "GET", null, null, null, "$", "ISO_8601", "customerId", null
        );

        List<Map<String, Object>> rows = adapter.extract(jdbcTemplate, config, Instant.EPOCH);
        assertEquals(1, rows.size());
        assertEquals("C1", rows.get(0).get("CUSTOMERID"));
        assertEquals("ACME Corp", rows.get(0).get("LEGALNAME"));
    }

    @Test
    void shouldFailSecurityValidationOnInvalidQuery() {
        ExtractionConfig invalidConfig = new ExtractionConfig(
                "DELETE FROM CUSTOMERS WHERE ID = 'C1'",
                "lastSyncWithBuffer", "customerId", 500, "GET", null, null, null, "$", "ISO_8601", "customerId", null
        );

        assertThrows(InvalidSqlExtractionException.class, () ->
                adapter.extract(jdbcTemplate, invalidConfig, Instant.EPOCH)
        );
    }
}
