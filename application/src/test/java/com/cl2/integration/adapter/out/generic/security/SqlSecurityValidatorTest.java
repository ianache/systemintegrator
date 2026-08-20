package com.cl2.integration.adapter.out.generic.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SqlSecurityValidatorTest {

    private final SqlSecurityValidator validator = new SqlSecurityValidator();

    @Test
    void shouldAcceptValidSelectQuery() {
        String query = "SELECT k.KUNNR AS customerId, k.NAME1 AS legalName FROM KNA1 k WHERE k.AEDAT >= :lastSyncWithBuffer ORDER BY k.AEDAT ASC";
        assertDoesNotThrow(() -> validator.validate(query, "lastSyncWithBuffer"));
    }

    @Test
    void shouldRejectDeleteQuery() {
        String query = "DELETE FROM KNA1 WHERE KUNNR = '123'";
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(query, "lastSyncWithBuffer"));
    }

    @Test
    void shouldRejectMultiStatement() {
        String query = "SELECT * FROM KNA1; DROP TABLE KNA1;";
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(query, "lastSyncWithBuffer"));
    }

    @Test
    void shouldRejectSystemCatalogAccess() {
        String query = "SELECT * FROM information_schema.tables WHERE 1=1 AND :lastSyncWithBuffer = :lastSyncWithBuffer";
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(query, "lastSyncWithBuffer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sys", "mysql", "pg_catalog", "master", "performance_schema"})
    void shouldRejectOtherForbiddenSystemCatalogs(String catalog) {
        String query = "SELECT * FROM " + catalog + ".tables WHERE 1=1 AND :lastSyncWithBuffer = :lastSyncWithBuffer";
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(query, "lastSyncWithBuffer"));
    }

    @Test
    void shouldRejectQueryWithoutWatermarkParamWhenExpected() {
        String query = "SELECT * FROM KNA1";
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(query, "lastSyncWithBuffer"));
    }

    @Test
    void shouldRejectBlankQuery() {
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate("   ", "lastSyncWithBuffer"));
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(null, "lastSyncWithBuffer"));
    }

    @Test
    void shouldRejectInsertAndUpdateAndDropQueries() {
        String insertQuery = "INSERT INTO KNA1 (KUNNR) VALUES ('123')";
        String updateQuery = "UPDATE KNA1 SET NAME1 = 'foo' WHERE 1=1 AND :lastSyncWithBuffer = :lastSyncWithBuffer";
        String dropQuery = "DROP TABLE KNA1";

        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(insertQuery, "lastSyncWithBuffer"));
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(updateQuery, "lastSyncWithBuffer"));
        assertThrows(InvalidSqlExtractionException.class, () -> validator.validate(dropQuery, "lastSyncWithBuffer"));
    }
}
