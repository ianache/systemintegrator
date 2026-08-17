package com.cl2.integration.adapter.out.generic;

import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.adapter.out.generic.security.SqlSecurityValidator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class GenericJdbcAdapter {

    private final SqlSecurityValidator sqlSecurityValidator;

    public GenericJdbcAdapter(SqlSecurityValidator sqlSecurityValidator) {
        this.sqlSecurityValidator = sqlSecurityValidator;
    }

    public List<Map<String, Object>> extract(NamedParameterJdbcTemplate jdbcTemplate, ExtractionConfig config, Instant watermarkTimestamp) {
        sqlSecurityValidator.validate(config.query(), config.watermarkParam());

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue(config.watermarkParam(), java.sql.Timestamp.from(watermarkTimestamp));

        jdbcTemplate.getJdbcTemplate().setFetchSize(config.fetchSize());
        return jdbcTemplate.queryForList(config.query(), params);
    }
}
