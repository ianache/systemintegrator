# Prometheus Metrics and Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement comprehensive Prometheus metrics with Micrometer for the whole integration platform, expose `/actuator/prometheus`, instrument all operational pipelines (Inbound Sync, Transactional Outbox, Kafka Inbox, JSLT Transformation, Outbound HTTP, OAuth2 Keycloak Cache, SQL Security), and provision Prometheus and Grafana with pre-configured dashboards in `compose.yaml`.

**Architecture:** A centralized domain metrics facade (`IntegrationMetrics`) backed by Spring Boot Actuator and Micrometer `PrometheusMeterRegistry`, instrumenting core sync, relay, dispatch, transformation and security components with low-cardinality tags. Prometheus and Grafana containers configured in `compose.yaml` with automated datasource and dashboard provisioning.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring Boot Actuator, Micrometer Prometheus, Prometheus 3.x, Grafana 11.x, Docker Compose.

## Global Constraints
- Low-cardinality tags only: never use `eventId` or `aggregateId` as Prometheus tags.
- Tag standard values: `tenant_id`, `business_domain`, `connector`, `external_source`, `http_status`, `status`, `engine`.
- Percentiles enabled for duration timers.
- All unit and integration tests must pass (`mvn test`).

---

### Task 1: Add Actuator & Micrometer Dependencies and Create `IntegrationMetrics` Facade

**Files:**
- Modify: `application/pom.xml`
- Create: `application/src/main/java/com/cl2/integration/infrastructure/metrics/IntegrationMetrics.java`
- Modify: `application/src/main/resources/application.yml`
- Test: `application/src/test/java/com/cl2/integration/infrastructure/metrics/IntegrationMetricsTest.java`

**Interfaces:**
- Produces:
  - `IntegrationMetrics` Spring component with typed methods for recording sync, outbox, inbox, transformation, HTTP, OAuth2 and security metrics.

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationMetricsTest {

    private MeterRegistry meterRegistry;
    private IntegrationMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new IntegrationMetrics(meterRegistry);
    }

    @Test
    @DisplayName("Should record sync run duration, rows extracted, and outbox saved events")
    void shouldRecordSyncAndOutboxMetrics() {
        metrics.recordSyncRun("tenant-1", "units", "sigo", "SUCCESS", 1.25, 45);
        metrics.recordOutboxEventSaved("tenant-1", "units", "units.upserted");
        metrics.recordOutboxRelay("tenant-1", "integration.units.events", 10, 0.45);

        assertThat(meterRegistry.find("integration.sync.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("integration.sync.duration").timer().count()).isEqualTo(1L);

        assertThat(meterRegistry.find("integration.sync.extracted.rows.total").counter()).isNotNull();
        assertThat(meterRegistry.find("integration.sync.extracted.rows.total").counter().count()).isEqualTo(45.0);

        assertThat(meterRegistry.find("integration.outbox.events.saved.total").counter()).isNotNull();
        assertThat(meterRegistry.find("integration.outbox.events.saved.total").counter().count()).isEqualTo(1.0);

        assertThat(meterRegistry.find("integration.outbox.relay.published.total").counter()).isNotNull();
        assertThat(meterRegistry.find("integration.outbox.relay.published.total").counter().count()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("Should record HTTP outbound, transformation, and OAuth2 cache metrics")
    void shouldRecordHttpAndOAuth2Metrics() {
        metrics.recordOutboundHttpRequest("tenant-1", "comsatel-unidad-rest", 200, 0.12);
        metrics.recordTransformation("tenant-1", "units", "JSLT", 0.005);
        metrics.recordOAuth2TokenRequest("tenant-1", "unidad", "SUCCESS");
        metrics.recordOAuth2TokenCacheHit("tenant-1", "unidad");
        metrics.recordSqlValidationBlocked("tenant-1", "FORBIDDEN_DML");

        assertThat(meterRegistry.find("integration.outbound.http.requests.total").counter()).isNotNull();
        assertThat(meterRegistry.find("integration.outbound.http.requests.total").tags("http_status", "200").counter().count()).isEqualTo(1.0);

        assertThat(meterRegistry.find("integration.transformation.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("integration.oauth2.token.requests.total").counter()).isNotNull();
        assertThat(meterRegistry.find("integration.oauth2.token.cache.hits.total").counter()).isNotNull();
        assertThat(meterRegistry.find("integration.sql.validation.blocked.total").counter()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl application "-Dtest=IntegrationMetricsTest"`
Expected: FAIL (class `IntegrationMetrics` not found).

- [ ] **Step 3: Write minimal implementation**

Update `application/pom.xml` to include:
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
```

Create `IntegrationMetrics.java`:
```java
package com.cl2.integration.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class IntegrationMetrics {

    private final MeterRegistry registry;

    public IntegrationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSyncRun(String tenantId, String domain, String source, String status, double durationSeconds, int rowCount) {
        String safeTenant = tenantId != null ? tenantId : "unknown";
        String safeDomain = domain != null ? domain : "unknown";
        String safeSource = source != null ? source : "unknown";
        String safeStatus = status != null ? status : "unknown";

        Timer.builder("integration.sync.duration")
                .description("Duration of Inbound data sync executions")
                .tags("tenant_id", safeTenant, "business_domain", safeDomain, "external_source", safeSource, "status", safeStatus)
                .register(registry)
                .record((long) (durationSeconds * 1000), TimeUnit.MILLISECONDS);

        if (rowCount > 0) {
            Counter.builder("integration.sync.extracted.rows.total")
                    .description("Total number of records extracted from external sources")
                    .tags("tenant_id", safeTenant, "business_domain", safeDomain, "external_source", safeSource)
                    .register(registry)
                    .increment(rowCount);
        }
    }

    public void recordSyncFailure(String tenantId, String domain, String errorType) {
        Counter.builder("integration.sync.failures.total")
                .description("Total number of sync execution failures")
                .tags("tenant_id", tenantId != null ? tenantId : "unknown",
                        "business_domain", domain != null ? domain : "unknown",
                        "error_type", errorType != null ? errorType : "unknown")
                .register(registry)
                .increment();
    }

    public void recordOutboxEventSaved(String tenantId, String domain, String eventType) {
        Counter.builder("integration.outbox.events.saved.total")
                .description("Total number of outbox events saved into database")
                .tags("tenant_id", tenantId != null ? tenantId : "unknown",
                        "business_domain", domain != null ? domain : "unknown",
                        "event_type", eventType != null ? eventType : "unknown")
                .register(registry)
                .increment();
    }

    public void recordOutboxRelay(String tenantId, String topic, int count, double durationSeconds) {
        String safeTenant = tenantId != null ? tenantId : "unknown";
        String safeTopic = topic != null ? topic : "unknown";

        Timer.builder("integration.outbox.relay.duration")
                .description("Duration of outbox relay batches to Kafka")
                .tags("tenant_id", safeTenant, "topic", safeTopic)
                .register(registry)
                .record((long) (durationSeconds * 1000), TimeUnit.MILLISECONDS);

        if (count > 0) {
            Counter.builder("integration.outbox.relay.published.total")
                    .description("Total number of outbox events published to Kafka")
                    .tags("tenant_id", safeTenant, "topic", safeTopic)
                    .register(registry)
                    .increment(count);
        }
    }

    public void recordInboxMessageConsumed(String tenantId, String domain, String topic) {
        Counter.builder("integration.inbox.consumed.total")
                .description("Total number of messages consumed from Kafka by Inbox listener")
                .tags("tenant_id", tenantId != null ? tenantId : "unknown",
                        "business_domain", domain != null ? domain : "unknown",
                        "topic", topic != null ? topic : "unknown")
                .register(registry)
                .increment();
    }

    public void recordDlqForwarded(String tenantId, String domain, String errorReason) {
        Counter.builder("integration.dlq.forwarded.total")
                .description("Total number of failed messages forwarded to Dead Letter Queue")
                .tags("tenant_id", tenantId != null ? tenantId : "unknown",
                        "business_domain", domain != null ? domain : "unknown",
                        "error_reason", errorReason != null ? errorReason : "unknown")
                .register(registry)
                .increment();
    }

    public void recordTransformation(String tenantId, String domain, String engine, double durationSeconds) {
        Timer.builder("integration.transformation.duration")
                .description("Duration of payload transformation execution")
                .tags("tenant_id", tenantId != null ? tenantId : "unknown",
                        "business_domain", domain != null ? domain : "unknown",
                        "engine", engine != null ? engine : "unknown")
                .register(registry)
                .record((long) (durationSeconds * 1000), TimeUnit.MILLISECONDS);
    }

    public void recordOutboundHttpRequest(String tenantId, String connector, int statusCode, double durationSeconds) {
        String safeTenant = tenantId != null ? tenantId : "unknown";
        String safeConnector = connector != null ? connector : "unknown";
        String result = (statusCode >= 200 && statusCode < 300) ? "SUCCESS" : "ERROR";

        Counter.builder("integration.outbound.http.requests.total")
                .description("Total number of outbound HTTP requests dispatched")
                .tags("tenant_id", safeTenant, "connector", safeConnector, "http_status", String.valueOf(statusCode), "result", result)
                .register(registry)
                .increment();

        Timer.builder("integration.outbound.http.duration")
                .description("Duration of outbound HTTP requests dispatched to external APIs")
                .tags("tenant_id", safeTenant, "connector", safeConnector, "result", result)
                .register(registry)
                .record((long) (durationSeconds * 1000), TimeUnit.MILLISECONDS);
    }

    public void recordOAuth2TokenRequest(String tenantId, String clientId, String status) {
        Counter.builder("integration.oauth2.token.requests.total")
                .description("Total OAuth2 token fetch requests to identity provider")
                .tags("tenant_id", tenantId != null ? tenantId : "unknown",
                        "client_id", clientId != null ? clientId : "unknown",
                        "status", status != null ? status : "unknown")
                .register(registry)
                .increment();
    }

    public void recordOAuth2TokenCacheHit(String tenantId, String clientId) {
        Counter.builder("integration.oauth2.token.cache.hits.total")
                .description("Total OAuth2 in-memory token cache hits")
                .tags("tenant_id", tenantId != null ? tenantId : "unknown",
                        "client_id", clientId != null ? clientId : "unknown")
                .register(registry)
                .increment();
    }

    public void recordSqlValidationBlocked(String tenantId, String violationType) {
        Counter.builder("integration.sql.validation.blocked.total")
                .description("Total SQL queries blocked by the security AST validator")
                .tags("tenant_id", tenantId != null ? tenantId : "unknown",
                        "violation_type", violationType != null ? violationType : "unknown")
                .register(registry)
                .increment();
    }
}
```

Update `application.yml` to expose Prometheus endpoint:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    prometheus:
      enabled: true
    health:
      show-details: always
  metrics:
    tags:
      application: systemintegrator-app
    distribution:
      percentiles-histogram:
        integration.outbound.http.duration: true
        integration.sync.duration: true
        integration.transformation.duration: true
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl application "-Dtest=IntegrationMetricsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add application/pom.xml application/src/main/java/com/cl2/integration/infrastructure/metrics/ application/src/main/resources/application.yml application/src/test/java/com/cl2/integration/infrastructure/metrics/IntegrationMetricsTest.java
git commit -m "feat(metrics): add actuator, micrometer-prometheus, and IntegrationMetrics facade"
```

---

### Task 2: Instrument Inbound Sync, Outbox Relay, and SQL Security Validator

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/outbox/OutboxRelayScheduler.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidator.java`
- Test: `application/src/test/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestratorTest.java`
- Test: `application/src/test/java/com/cl2/integration/integration/outbox/OutboxRelaySchedulerTest.java`

**Interfaces:**
- Consumes: `IntegrationMetrics` in `IntegrationSyncOrchestrator`, `OutboxRelayScheduler`, and `SqlSecurityValidator`.

- [ ] **Step 1: Write the failing test assertion**

In `IntegrationSyncOrchestratorTest.java`, assert that `metrics.recordSyncRun(...)` is invoked upon successful extraction and `metrics.recordOutboxEventSaved(...)` is called for each saved event.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl application "-Dtest=IntegrationSyncOrchestratorTest"`
Expected: FAIL (constructor parameter mismatch or missing metric interaction).

- [ ] **Step 3: Write minimal implementation**

Inject `IntegrationMetrics` into `IntegrationSyncOrchestrator.java`:
```java
// Inside run(IntegrationProfile profile):
long startNanos = System.nanoTime();
// ...
for (Map<String, Object> row : rows) {
    // ...
    outboxRepository.save(OutboxEvent.pending(...));
    metrics.recordOutboxEventSaved(profile.tenantId().toString(), profile.businessDomain(), eventType);
}
// On success:
double duration = (System.nanoTime() - startNanos) / 1_000_000_000.0;
metrics.recordSyncRun(profile.tenantId().toString(), profile.businessDomain(), profile.externalSource(), "SUCCESS", duration, rows.size());
```

Inject `IntegrationMetrics` into `OutboxRelayScheduler.java`:
```java
// Inside relayEvents():
long startNanos = System.nanoTime();
// After publishing batch to kafka:
double duration = (System.nanoTime() - startNanos) / 1_000_000_000.0;
metrics.recordOutboxRelay(event.tenantId().toString(), event.topic(), 1, duration);
```

Inject `IntegrationMetrics` into `SqlSecurityValidator.java`:
```java
// In catch blocks or when query is rejected:
if (metrics != null) {
    metrics.recordSqlValidationBlocked(tenantId, violationType);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl application "-Dtest=IntegrationSyncOrchestratorTest,OutboxRelaySchedulerTest,SqlSecurityValidatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java application/src/main/java/com/cl2/integration/integration/outbox/OutboxRelayScheduler.java application/src/main/java/com/cl2/integration/adapter/out/generic/security/SqlSecurityValidator.java application/src/test/java/
git commit -m "feat(metrics): instrument Inbound Sync, Outbox Relay, and SQL Security Validator"
```

---

### Task 3: Instrument Kafka Inbox, Transformation Service, Http Outbound, and OAuth2 Token Cache

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/integration/inbox/KafkaInboxListener.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/transformation/TransformationService.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/http/HttpOutboundClient.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManager.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/out/http/HttpOutboundClientTest.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManagerTest.java`

**Interfaces:**
- Consumes: `IntegrationMetrics` in `KafkaInboxListener`, `TransformationService`, `HttpOutboundClient`, and `OAuth2TokenCacheManager`.

- [ ] **Step 1: Write failing test assertions**

In `HttpOutboundClientTest.java` and `OAuth2TokenCacheManagerTest.java`, add assertions verifying metrics are recorded for requests and cache hits/misses.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl application "-Dtest=HttpOutboundClientTest,OAuth2TokenCacheManagerTest"`
Expected: FAIL (missing metric recording).

- [ ] **Step 3: Write minimal implementation**

Update `HttpOutboundClient.java`:
```java
// Inside send():
long start = System.nanoTime();
try {
    restClient.post()...
    double duration = (System.nanoTime() - start) / 1_000_000_000.0;
    if (metrics != null) {
        metrics.recordOutboundHttpRequest(tenantId != null ? tenantId.toString() : "unknown", connector, 200, duration);
    }
} catch (RestClientResponseException ex) {
    double duration = (System.nanoTime() - start) / 1_000_000_000.0;
    if (metrics != null) {
        metrics.recordOutboundHttpRequest(tenantId != null ? tenantId.toString() : "unknown", connector, ex.getStatusCode().value(), duration);
    }
    throw ...;
}
```

Update `OAuth2TokenCacheManager.java`:
```java
// On cache hit:
if (metrics != null) {
    metrics.recordOAuth2TokenCacheHit(tenantId, clientId);
}
// On token fetch:
if (metrics != null) {
    metrics.recordOAuth2TokenRequest(tenantId, clientId, "SUCCESS");
}
```

Update `TransformationService.java`:
```java
long start = System.nanoTime();
String result = engine.transform(payload, profile);
double duration = (System.nanoTime() - start) / 1_000_000_000.0;
if (metrics != null) {
    metrics.recordTransformation(profile.tenantId().toString(), profile.businessDomain(), profile.configuration().transformationEngine().name(), duration);
}
```

Update `KafkaInboxListener.java`:
```java
// On message received:
metrics.recordInboxMessageConsumed(tenantIdStr, derivedDomain, record.topic());
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl application "-Dtest=HttpOutboundClientTest,OAuth2TokenCacheManagerTest,KafkaInboxListenerTest,TransformationServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add application/src/main/java/com/cl2/integration/adapter/out/http/HttpOutboundClient.java application/src/main/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManager.java application/src/main/java/com/cl2/integration/integration/transformation/TransformationService.java application/src/main/java/com/cl2/integration/integration/inbox/KafkaInboxListener.java application/src/test/
git commit -m "feat(metrics): instrument Inbox listener, TransformationService, HttpOutboundClient, and OAuth2TokenCacheManager"
```

---

### Task 4: Prometheus & Grafana Provisioning in Docker Compose & End-to-End Prometheus Actuator Test

**Files:**
- Create: `deploy/prometheus/prometheus.yml`
- Create: `deploy/grafana/provisioning/datasources/datasource.yml`
- Create: `deploy/grafana/provisioning/dashboards/dashboards.yml`
- Create: `deploy/grafana/provisioning/dashboards/integration-platform-dashboard.json`
- Modify: `compose.yaml`
- Create test: `application/src/test/java/com/cl2/integration/infrastructure/metrics/PrometheusActuatorIntegrationTest.java`
- Test: Full reactor test suite via `mvn test`

**Interfaces:**
- Exposes:
  - `http://localhost:9090` (Prometheus)
  - `http://localhost:3000` (Grafana)
  - `http://localhost:8080/actuator/prometheus` (Metrics scrape endpoint)

- [ ] **Step 1: Write the integration test**

```java
package com.cl2.integration.infrastructure.metrics;

import com.cl2.integration.IntegrationApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusActuatorIntegrationTest extends IntegrationApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Should expose /actuator/prometheus endpoint containing integration metrics")
    void shouldExposePrometheusEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("jvm_memory_used_bytes")
                .contains("integration_sync_duration_seconds")
                .contains("integration_outbound_http_requests_total");
    }
}
```

- [ ] **Step 2: Create Prometheus config file**

Create `deploy/prometheus/prometheus.yml`:
```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: 'systemintegrator-app'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 5s
    static_configs:
      - targets: ['integration-app:8080']
```

- [ ] **Step 3: Create Grafana provisioning files**

Create `deploy/grafana/provisioning/datasources/datasource.yml`:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://integration-prometheus:9090
    isDefault: true
```

Create `deploy/grafana/provisioning/dashboards/dashboards.yml`:
```yaml
apiVersion: 1
providers:
  - name: 'Integration Platform'
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    allowUiUpdates: true
    options:
      path: /etc/grafana/provisioning/dashboards
```

Create `deploy/grafana/provisioning/dashboards/integration-platform-dashboard.json` with visual panels for:
- Inbound Extracted Rows Rate
- Outbound HTTP Request Rate by Status Code
- HTTP Outbound Latency (p50, p95, p99)
- Dead Letter Queue Forwarded Rate
- OAuth2 Cache Hit vs Miss Ratio
- Circuit Breaker Status

- [ ] **Step 4: Update `compose.yaml` with `prometheus` and `grafana` services**

Add `prometheus` and `grafana` containers in `compose.yaml` connected to `integration-internal` network.

- [ ] **Step 5: Run full test suite and verify**

Run: `mvn test`
Expected: 100% test pass rate across all modules.

- [ ] **Step 6: Commit**

```bash
git add deploy/ compose.yaml application/src/test/java/com/cl2/integration/infrastructure/metrics/PrometheusActuatorIntegrationTest.java
git commit -m "feat(observability): add Prometheus and Grafana provisioning to compose.yaml and verify actuator endpoint"
```
