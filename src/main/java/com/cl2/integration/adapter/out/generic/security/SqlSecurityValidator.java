package com.cl2.integration.adapter.out.generic.security;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;

import java.util.Locale;
import java.util.Set;

public class SqlSecurityValidator {

    private static final Set<String> FORBIDDEN_CATALOGS = Set.of(
            "information_schema", "sys", "mysql", "pg_catalog", "master", "performance_schema"
    );

    public void validate(String query, String watermarkParam) {
        if (query == null || query.isBlank()) {
            throw new InvalidSqlExtractionException("Extraction query must not be blank");
        }

        if (query.contains(";")) {
            throw new InvalidSqlExtractionException("Multi-statement SQL queries containing ';' are strictly prohibited");
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (String catalog : FORBIDDEN_CATALOGS) {
            if (lowerQuery.contains(catalog)) {
                throw new InvalidSqlExtractionException("Access to system catalog '" + catalog + "' is strictly prohibited");
            }
        }

        if (watermarkParam != null && !watermarkParam.isBlank()) {
            if (!query.contains(":" + watermarkParam)) {
                throw new InvalidSqlExtractionException("Extraction query must contain named parameter binding ':" + watermarkParam + "'");
            }
        }

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(query);
            if (statements.getStatements().size() != 1) {
                throw new InvalidSqlExtractionException("Multi-statement SQL queries are strictly prohibited");
            }

            Statement statement = statements.getStatements().get(0);
            if (!(statement instanceof Select)) {
                throw new InvalidSqlExtractionException("Only SELECT queries are allowed for data extraction");
            }
        } catch (Exception e) {
            if (e instanceof InvalidSqlExtractionException invalidEx) {
                throw invalidEx;
            }
            throw new InvalidSqlExtractionException("Invalid SQL syntax in extraction query: " + e.getMessage(), e);
        }
    }
}
