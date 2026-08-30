package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.FlowMetricsSummary;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class FlowExecutionPersistenceAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");

    @Autowired
    private FlowExecutionPersistenceAdapter adapter;

    @Autowired
    private FlowPersistenceAdapter flowAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID flowId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM flow_execution");
        jdbcTemplate.update("DELETE FROM flow_version");
        jdbcTemplate.update("DELETE FROM flow");
        flowId = flowAdapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X")).id();
    }

    @Test
    void savesAndReadsBackAnExecution() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.MICROS);
        FlowExecution execution = FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, started, started.plusMillis(500), null);

        FlowExecution saved = adapter.save(TENANT_ID, execution);

        assertThat(saved.durationMs()).isEqualTo(500);
    }

    @Test
    void countsOnlyExecutionsWithinTheWindowAndForTheTenant() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant withinWindow = now.minus(1, ChronoUnit.HOURS);
        Instant outsideWindow = now.minus(25, ChronoUnit.HOURS);

        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, withinWindow, withinWindow.plusMillis(100), null));
        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, outsideWindow, outsideWindow.plusMillis(100), null));
        adapter.save(OTHER_TENANT_ID, FlowExecution.report(UUID.randomUUID(), OTHER_TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, withinWindow, withinWindow.plusMillis(100), null));

        FlowMetricsSummary summary = adapter.executionMetrics(TENANT_ID, now.minus(24, ChronoUnit.HOURS));

        assertThat(summary.executions24h()).isEqualTo(1);
    }

    @Test
    void computesErrorRateAsZeroWhenThereAreNoExecutions() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        FlowMetricsSummary summary = adapter.executionMetrics(TENANT_ID, now.minus(24, ChronoUnit.HOURS));

        assertThat(summary.executions24h()).isZero();
        assertThat(summary.errorRatePct()).isEqualTo(0.0);
        assertThat(summary.p95DurationMs()).isNull();
    }

    @Test
    void computesErrorRateWithMixedResults() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t = now.minus(1, ChronoUnit.HOURS);

        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, t, t.plusMillis(100), null));
        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, t, t.plusMillis(100), null));
        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.SUCCESS, t, t.plusMillis(100), null));
        adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                FlowExecutionStatus.FAILURE, t, t.plusMillis(100), "boom"));

        FlowMetricsSummary summary = adapter.executionMetrics(TENANT_ID, now.minus(24, ChronoUnit.HOURS));

        assertThat(summary.executions24h()).isEqualTo(4);
        assertThat(summary.errorRatePct()).isEqualTo(25.0);
    }

    @Test
    void computesP95OverTwentyKnownDurations() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t = now.minus(1, ChronoUnit.HOURS);

        for (int i = 1; i <= 20; i++) {
            long durationMs = i * 100L;
            adapter.save(TENANT_ID, FlowExecution.report(UUID.randomUUID(), TENANT_ID, flowId, 1,
                    FlowExecutionStatus.SUCCESS, t, t.plusMillis(durationMs), null));
        }

        FlowMetricsSummary summary = adapter.executionMetrics(TENANT_ID, now.minus(24, ChronoUnit.HOURS));

        assertThat(summary.p95DurationMs()).isEqualTo(1900L);
    }
}
