package com.cl2.integration.domain.port;

import com.cl2.integration.application.FlowMetricsSummary;
import com.cl2.integration.domain.model.FlowExecution;
import java.time.Instant;
import java.util.UUID;

public interface FlowExecutionRepository {

    FlowExecution save(UUID tenantId, FlowExecution execution);

    FlowMetricsSummary executionMetrics(UUID tenantId, Instant since);
}
