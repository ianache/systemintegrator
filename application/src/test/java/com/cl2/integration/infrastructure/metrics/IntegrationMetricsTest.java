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
