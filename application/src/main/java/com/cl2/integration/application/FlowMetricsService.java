package com.cl2.integration.application;

import com.cl2.integration.application.command.ReportFlowExecutionCommand;
import com.cl2.integration.application.exception.FlowExecutionInvalidException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.port.FlowExecutionRepository;
import com.cl2.integration.domain.port.FlowRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowMetricsService {

    private final FlowRepository flowRepository;
    private final FlowExecutionRepository flowExecutionRepository;

    public FlowMetricsService(FlowRepository flowRepository, FlowExecutionRepository flowExecutionRepository) {
        this.flowRepository = flowRepository;
        this.flowExecutionRepository = flowExecutionRepository;
    }

    @Transactional
    public FlowExecution report(UUID tenantId, UUID flowId, ReportFlowExecutionCommand command) {
        Flow flow = flowRepository.findById(tenantId, flowId);
        try {
            FlowExecution execution = FlowExecution.report(UUID.randomUUID(), tenantId, flow.id(),
                    command.flowVersionNumber(), command.status(), command.startedAt(), command.finishedAt(),
                    command.errorMessage());
            return flowExecutionRepository.save(tenantId, execution);
        } catch (IllegalArgumentException e) {
            throw new FlowExecutionInvalidException(e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public FlowMetricsSummary summarize(UUID tenantId) {
        long publishedFlowCount = flowRepository.findAll(tenantId, true).stream()
                .filter(flow -> flow.activeVersionNumber() != null)
                .count();
        Instant since = Instant.now().minus(Duration.ofHours(24));
        return flowExecutionRepository.executionMetrics(tenantId, since).withPublishedFlowCount(publishedFlowCount);
    }
}
