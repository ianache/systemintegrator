package com.cl2.integration.application;

import com.cl2.integration.application.command.ReportFlowExecutionCommand;
import com.cl2.integration.application.exception.FlowExecutionInvalidException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStatus;
import com.cl2.integration.domain.port.FlowExecutionRepository;
import com.cl2.integration.domain.port.FlowRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

class FlowMetricsServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID FLOW_ID = UUID.randomUUID();

    @Mock
    private FlowRepository flowRepository;

    @Mock
    private FlowExecutionRepository flowExecutionRepository;

    private FlowMetricsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new FlowMetricsService(flowRepository, flowExecutionRepository);
    }

    @Test
    void reportsAnExecutionForAnExistingFlow() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");
        given(flowRepository.findById(TENANT_ID, FLOW_ID)).willReturn(flow);
        given(flowExecutionRepository.save(eq(TENANT_ID), any(FlowExecution.class)))
                .willAnswer(invocation -> invocation.getArgument(1));

        Instant started = Instant.parse("2026-08-30T00:00:00Z");
        Instant finished = Instant.parse("2026-08-30T00:00:01Z");
        ReportFlowExecutionCommand command = new ReportFlowExecutionCommand(1, FlowExecutionStatus.SUCCESS,
                started, finished, null);

        FlowExecution result = service.report(TENANT_ID, FLOW_ID, command);

        assertThat(result.flowId()).isEqualTo(FLOW_ID);
        assertThat(result.durationMs()).isEqualTo(1000);
        then(flowExecutionRepository).should().save(eq(TENANT_ID), any(FlowExecution.class));
    }

    @Test
    void rejectsAFinishTimeBeforeTheStartTime() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");
        given(flowRepository.findById(TENANT_ID, FLOW_ID)).willReturn(flow);

        Instant started = Instant.parse("2026-08-30T00:00:01Z");
        Instant finished = Instant.parse("2026-08-30T00:00:00Z");
        ReportFlowExecutionCommand command = new ReportFlowExecutionCommand(1, FlowExecutionStatus.FAILURE,
                started, finished, "boom");

        assertThatThrownBy(() -> service.report(TENANT_ID, FLOW_ID, command))
                .isInstanceOf(FlowExecutionInvalidException.class);
    }

    @Test
    void summarizeFillsInThePublishedFlowCountFromTheFlowRepository() {
        Flow published = Flow.create(UUID.randomUUID(), TENANT_ID, "flow/a", "A").withActiveVersion(1);
        Flow draft = Flow.create(UUID.randomUUID(), TENANT_ID, "flow/b", "B");
        given(flowRepository.findAll(TENANT_ID, true)).willReturn(List.of(published, draft));
        given(flowExecutionRepository.executionMetrics(eq(TENANT_ID), any(Instant.class)))
                .willReturn(new FlowMetricsSummary(0, 10, 5.0, 200L, 120L, 6L, 2));

        FlowMetricsSummary summary = service.summarize(TENANT_ID);

        assertThat(summary.publishedFlowCount()).isEqualTo(1);
        assertThat(summary.executions24h()).isEqualTo(10);
        assertThat(summary.errorRatePct()).isEqualTo(5.0);
        assertThat(summary.p95DurationMs()).isEqualTo(200L);
        assertThat(summary.p50DurationMs()).isEqualTo(120L);
        assertThat(summary.lastRunStepCount()).isEqualTo(6L);
        assertThat(summary.failedStepCount()).isEqualTo(2);
    }

    @Test
    void rowMetricsDelegatesToTheFlowExecutionRepositoryScopedByFlow() {
        given(flowExecutionRepository.executionMetricsForFlow(eq(TENANT_ID), eq(FLOW_ID), any(Instant.class)))
                .willReturn(new FlowMetricsSummary(0, 4, 25.0, 340L, null, null, 0));

        FlowMetricsSummary summary = service.rowMetrics(TENANT_ID, FLOW_ID);

        assertThat(summary.executions24h()).isEqualTo(4);
        assertThat(summary.errorRatePct()).isEqualTo(25.0);
        assertThat(summary.p95DurationMs()).isEqualTo(340L);
    }
}
