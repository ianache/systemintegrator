package com.cl2.integration.domain.port;

import com.cl2.integration.application.FlowMetricsSummary;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowExecutionRepository {

    FlowExecution save(UUID tenantId, FlowExecution execution);

    void saveSteps(UUID flowExecutionId, List<FlowExecutionStep> steps);

    FlowMetricsSummary executionMetrics(UUID tenantId, Instant since);

    FlowMetricsSummary executionMetricsForFlow(UUID tenantId, UUID flowId, Instant since);

    List<FlowExecution> findByFlow(UUID tenantId, UUID flowId);

    Optional<FlowExecution> findById(UUID tenantId, UUID flowId, UUID executionId);

    List<FlowExecutionStep> findSteps(UUID flowExecutionId);
}
