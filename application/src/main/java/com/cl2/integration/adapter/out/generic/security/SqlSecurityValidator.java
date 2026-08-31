package com.cl2.integration.adapter.out.generic.security;

import com.cl2.integration.infrastructure.metrics.IntegrationMetrics;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class SqlSecurityValidator {

    private static final Set<String> FORBIDDEN_CATALOGS = Set.of(
            "information_schema", "sys", "mysql", "pg_catalog", "master", "performance_schema"
    );

    private final IntegrationMetrics metrics;

    public SqlSecurityValidator() {
        this(null);
    }

    @Autowired
    public SqlSecurityValidator(@Autowired(required = false) IntegrationMetrics metrics) {
        this.metrics = metrics;
    }

    public void validate(String query, String watermarkParam) {
        validate("unknown", query, watermarkParam);
    }

    public void validate(String tenantId, String query, String watermarkParam) {
        String safeTenant = tenantId != null ? tenantId : "unknown";

        if (query == null || query.isBlank()) {
            if (metrics != null) {
                metrics.recordSqlValidationBlocked(safeTenant, "BLANK_QUERY");
            }
            throw new InvalidSqlExtractionException("Extraction query must not be blank");
        }

        if (query.contains(";")) {
            if (metrics != null) {
                metrics.recordSqlValidationBlocked(safeTenant, "MULTI_STATEMENT");
            }
            throw new InvalidSqlExtractionException("Multi-statement SQL queries containing ';' are strictly prohibited");
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (String catalog : FORBIDDEN_CATALOGS) {
            if (lowerQuery.contains(catalog)) {
                if (metrics != null) {
                    metrics.recordSqlValidationBlocked(safeTenant, "FORBIDDEN_CATALOG");
                }
                throw new InvalidSqlExtractionException("Access to system catalog '" + catalog + "' is strictly prohibited");
            }
        }

        if (watermarkParam != null && !watermarkParam.isBlank()) {
            if (!query.contains(":" + watermarkParam)) {
                if (metrics != null) {
                    metrics.recordSqlValidationBlocked(safeTenant, "MISSING_WATERMARK_PARAM");
                }
                throw new InvalidSqlExtractionException("Extraction query must contain named parameter binding ':" + watermarkParam + "'");
            }
        }

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(query);
            if (statements.getStatements().size() != 1) {
                if (metrics != null) {
                    metrics.recordSqlValidationBlocked(safeTenant, "MULTI_STATEMENT");
                }
                throw new InvalidSqlExtractionException("Multi-statement SQL queries are strictly prohibited");
            }

            Statement statement = statements.getStatements().get(0);
            if (!(statement instanceof Select)) {
                if (metrics != null) {
                    metrics.recordSqlValidationBlocked(safeTenant, "FORBIDDEN_DML");
                }
                throw new InvalidSqlExtractionException("Only SELECT queries are allowed for data extraction");
            }
        } catch (Exception e) {
            if (e instanceof InvalidSqlExtractionException invalidEx) {
                throw invalidEx;
            }
            if (metrics != null) {
                metrics.recordSqlValidationBlocked(safeTenant, "INVALID_SQL_SYNTAX");
            }
            throw new InvalidSqlExtractionException("Invalid SQL syntax in extraction query: " + e.getMessage(), e);
        }
    }
}
