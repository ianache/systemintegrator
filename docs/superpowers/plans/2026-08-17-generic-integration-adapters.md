# Generic Declarative Integration Adapters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a Zero-Code Declarative Integration Adapter Engine (`GenericJdbcAdapter`, `GenericRestAdapter`, `SqlSecurityValidator`, `OAuth2TokenCacheManager`) to allow database and REST API integrations to be configured 100% via `IntegrationProfile` metadata without writing Java code per integration.

**Architecture:** Extended Hexagonal Architecture where generic adapter components parse profile metadata (`extractionConfig`, `authConfig`), validate security rules using a JSqlParser AST guardrail, resolve Vault credentials, execute queries/HTTP requests, map JSON payloads via JSLT, and log events to MySQL Outbox/Inbox inside a single transaction.

**Tech Stack:** Java 21, Spring Boot 3.4.5, JSqlParser 4.9, Spring `NamedParameterJdbcTemplate`, Spring `RestClient`, Resilience4j, Jackson, WireMock, Testcontainers.

## Global Constraints
- Java 21 features (records, pattern matching, text blocks).
- Multi-tenancy isolation enforced via `X-Tenant-ID` / `TenantContext`.
- Strict SQL AST validation enforcing `SELECT` statements only, prohibiting DML/DDL, multi-statements, and system catalogs.
- Zero plaintext password or secret logging.

---

## File Structure

```
src/main/java/com/cl2/integration/adapter/out/generic
├── GenericJdbcAdapter.java                  # Generic JDBC database pulling engine
├── GenericRestAdapter.java                  # Generic HTTP/REST API pulling engine
├── security
│   ├── SqlSecurityValidator.java            # JSqlParser AST SELECT-only validator
│   ├── InvalidSqlExtractionException.java   # Exception for SQL security violations
│   └── OAuth2TokenCacheManager.java         # Thread-safe in-memory OAuth2/OIDC token manager
└── model
    ├── ExtractionConfig.java                # DTO for JDBC/REST extraction configuration
    └── AuthConfig.java                      # DTO for OAuth2, Bearer, Basic, API Key auth

src/test/java/com/cl2/integration/adapter/out/generic
├── security
│   ├── SqlSecurityValidatorTest.java        # Unit tests for SQL security rules
│   └── OAuth2TokenCacheManagerTest.java     # Unit tests for OAuth2 token lifecycle & refresh
├── GenericJdbcAdapterTest.java              # Integration test with Testcontainers MySQL
└── GenericRestAdapterTest.java              # Integration test with WireMock OIDC/REST endpoint
```

---

### Task 1: SQL Security Guardrail (`SqlSecurityValidator`)

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/out/generic/security/InvalidSqlExtractionException.java`
- Create: `src/main/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidator.java`
- Test: `src/test/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidatorTest.java`

**Interfaces:**
- Consumes: JSqlParser AST parser
- Produces: `SqlSecurityValidator.validate(String query, String expectedWatermarkParam)`

- [ ] **Step 1: Write failing unit test for `SqlSecurityValidator`**

Create `src/test/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidatorTest.java`:
```java
package com.cl2.integration.adapter.out.generic.security;

import org.junit.jupiter.api.Test;
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
}
```

- [ ] **Step 2: Run test to verify it fails**
Run `mvn test -Dtest=SqlSecurityValidatorTest`
Expected: Compilation failure or Test failure (Classes don't exist yet).

- [ ] **Step 3: Implement `InvalidSqlExtractionException` and `SqlSecurityValidator`**

Create `src/main/java/com/cl2/integration/adapter/out/generic/security/InvalidSqlExtractionException.java`:
```java
package com.cl2.integration.adapter.out.generic.security;

public class InvalidSqlExtractionException extends RuntimeException {
    public InvalidSqlExtractionException(String message) {
        super(message);
    }
    public InvalidSqlExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create `src/main/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidator.java`:
```java
package com.cl2.integration.adapter.out.generic.security;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
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
            Statement statement = CCJSqlParserUtil.parse(query);
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
```

- [ ] **Step 4: Run test to verify it passes**
Run `mvn test -Dtest=SqlSecurityValidatorTest`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/cl2/integration/adapter/out/generic/security/
git add src/test/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidatorTest.java
git commit -m "feat: add SqlSecurityValidator with JSqlParser AST SELECT-only guardrail"
```

---

### Task 2: DTOs (`ExtractionConfig` & `AuthConfig`) and Domain Model Validation

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfig.java`
- Create: `src/main/java/com/cl2/integration/adapter/out/generic/model/AuthConfig.java`
- Modify: `src/main/java/com/cl2/integration/domain/model/IntegrationProfileConfiguration.java`

- [ ] **Step 1: Write failing unit test for `ExtractionConfig` and `AuthConfig` parsing**

Create `src/test/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfigTest.java`:
```java
package com.cl2.integration.adapter.out.generic.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtractionConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseJdbcExtractionConfig() throws Exception {
        String json = "{\"query\":\"SELECT * FROM KNA1 WHERE AEDAT >= :lastSyncWithBuffer\",\"watermarkParam\":\"lastSyncWithBuffer\",\"keyColumn\":\"KUNNR\",\"fetchSize\":500}";
        ExtractionConfig config = objectMapper.readValue(json, ExtractionConfig.class);
        assertEquals("KUNNR", config.keyColumn());
        assertEquals(500, config.fetchSize());
    }

    @Test
    void shouldParseOauth2AuthConfig() throws Exception {
        String json = "{\"authType\":\"OAUTH2_CLIENT_CREDENTIALS\",\"tokenUrl\":\"https://oauth.test/token\",\"clientId\":\"my-client\",\"clientSecretRef\":\"secret/oauth\"}";
        AuthConfig auth = objectMapper.readValue(json, AuthConfig.class);
        assertEquals("OAUTH2_CLIENT_CREDENTIALS", auth.authType());
        assertEquals("my-client", auth.clientId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run `mvn test -Dtest=ExtractionConfigTest`
Expected: FAIL (Classes don't exist).

- [ ] **Step 3: Implement `ExtractionConfig` and `AuthConfig` records**

Create `src/main/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfig.java`:
```java
package com.cl2.integration.adapter.out.generic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionConfig(
        String query,
        String watermarkParam,
        String keyColumn,
        Integer fetchSize,
        String method,
        String path,
        Map<String, String> queryParams,
        Map<String, String> headers,
        String responseJsonPath,
        String watermarkFormat,
        String keyProperty
) {
    public ExtractionConfig {
        if (watermarkParam == null || watermarkParam.isBlank()) {
            watermarkParam = "lastSyncWithBuffer";
        }
        if (fetchSize == null || fetchSize <= 0) {
            fetchSize = 500;
        }
        if (method == null || method.isBlank()) {
            method = "GET";
        }
        if (responseJsonPath == null || responseJsonPath.isBlank()) {
            responseJsonPath = "$";
        }
        if (watermarkFormat == null || watermarkFormat.isBlank()) {
            watermarkFormat = "ISO_8601";
        }
    }
}
```

Create `src/main/java/com/cl2/integration/adapter/out/generic/model/AuthConfig.java`:
```java
package com.cl2.integration.adapter.out.generic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthConfig(
        String authType,
        String tokenUrl,
        String clientId,
        String clientSecretRef,
        String scope,
        String tokenRef,
        String credentialRef,
        String headerName,
        String keyRef
) {}
```

- [ ] **Step 4: Run test to verify it passes**
Run `mvn test -Dtest=ExtractionConfigTest`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/cl2/integration/adapter/out/generic/model/
git add src/test/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfigTest.java
git commit -m "feat: add ExtractionConfig and AuthConfig records"
```

---

### Task 3: Thread-Safe OAuth2/OIDC Token Cache Manager

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManager.java`
- Test: `src/test/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManagerTest.java`

- [ ] **Step 1: Write failing unit test for `OAuth2TokenCacheManager`**

Create `src/test/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManagerTest.java`:
```java
package com.cl2.integration.adapter.out.generic.security;

import com.cl2.integration.adapter.out.generic.model.AuthConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OAuth2TokenCacheManagerTest {

    private final OAuth2TokenCacheManager cacheManager = new OAuth2TokenCacheManager((url, client, secret, scope) -> "mock-jwt-bearer-token");

    @Test
    void shouldCacheAndReturnToken() {
        AuthConfig authConfig = new AuthConfig("OAUTH2_CLIENT_CREDENTIALS", "https://oauth.test/token", "client1", "secret/ref", "read", null, null, null, null);
        String token1 = cacheManager.getAccessToken("tenant1", authConfig);
        String token2 = cacheManager.getAccessToken("tenant1", authConfig);

        assertEquals("mock-jwt-bearer-token", token1);
        assertEquals(token1, token2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run `mvn test -Dtest=OAuth2TokenCacheManagerTest`
Expected: FAIL (Class missing).

- [ ] **Step 3: Implement `OAuth2TokenCacheManager`**

Create `src/main/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManager.java`:
```java
package com.cl2.integration.adapter.out.generic.security;

import com.cl2.integration.adapter.out.generic.model.AuthConfig;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OAuth2TokenCacheManager {

    @FunctionalInterface
    public interface TokenFetcher {
        String fetchToken(String tokenUrl, String clientId, String clientSecretRef, String scope);
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}

    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final TokenFetcher tokenFetcher;

    public OAuth2TokenCacheManager(TokenFetcher tokenFetcher) {
        this.tokenFetcher = tokenFetcher;
    }

    public String getAccessToken(String tenantId, AuthConfig authConfig) {
        String cacheKey = tenantId + ":" + authConfig.clientId() + ":" + authConfig.tokenUrl();
        CachedToken cached = tokenCache.get(cacheKey);

        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return cached.accessToken();
        }

        String freshToken = tokenFetcher.fetchToken(
                authConfig.tokenUrl(),
                authConfig.clientId(),
                authConfig.clientSecretRef(),
                authConfig.scope()
        );

        tokenCache.put(cacheKey, new CachedToken(freshToken, Instant.now().plusSeconds(3600)));
        return freshToken;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**
Run `mvn test -Dtest=OAuth2TokenCacheManagerTest`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManager.java
git add src/test/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManagerTest.java
git commit -m "feat: add thread-safe OAuth2TokenCacheManager"
```

---

### Task 4: `GenericJdbcAdapter` & MySQL Integration Verification

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapter.java`
- Test: `src/test/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapterTest.java`

- [ ] **Step 1: Write failing integration test for `GenericJdbcAdapter`**

Create `src/test/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapterTest.java`:
```java
package com.cl2.integration.adapter.out.generic;

import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.adapter.out.generic.security.SqlSecurityValidator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GenericJdbcAdapterTest {

    @Test
    void shouldExtractDataViaJdbcTemplate() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", "sa", "");
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(ds);
        jdbcTemplate.getJdbcTemplate().execute("CREATE TABLE CUSTOMERS (ID VARCHAR(50), NAME VARCHAR(50), UPDATED_AT TIMESTAMP)");
        jdbcTemplate.getJdbcTemplate().execute("INSERT INTO CUSTOMERS VALUES ('C1', 'ACME Corp', NOW())");

        SqlSecurityValidator validator = new SqlSecurityValidator();
        GenericJdbcAdapter adapter = new GenericJdbcAdapter(validator);

        ExtractionConfig config = new ExtractionConfig(
                "SELECT ID AS customerId, NAME AS legalName FROM CUSTOMERS WHERE UPDATED_AT >= :lastSyncWithBuffer",
                "lastSyncWithBuffer", "customerId", 500, "GET", null, null, null, "$", "ISO_8601", "customerId"
        );

        List<Map<String, Object>> rows = adapter.extract(jdbcTemplate, config, Instant.EPOCH);
        assertEquals(1, rows.size());
        assertEquals("C1", rows.get(0).get("CUSTOMERID"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run `mvn test -Dtest=GenericJdbcAdapterTest`
Expected: FAIL (Class missing).

- [ ] **Step 3: Implement `GenericJdbcAdapter`**

Create `src/main/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapter.java`:
```java
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
```

- [ ] **Step 4: Run test to verify it passes**
Run `mvn test -Dtest=GenericJdbcAdapterTest`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapter.java
git add src/test/java/com/cl2/integration/adapter/out/generic/GenericJdbcAdapterTest.java
git commit -m "feat: implement GenericJdbcAdapter with parameter extraction and security validation"
```

---

## Plan Handoff Choices
After saving the plan, offer execution options to the user:
1. **Subagent-Driven (recommended)** - Execute task-by-task using fresh subagents.
2. **Inline Execution** - Execute tasks in this session using executing-plans.
