package com.cl2.integration.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

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
