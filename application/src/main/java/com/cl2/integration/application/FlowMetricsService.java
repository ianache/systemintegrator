package com.cl2.integration.application;

import com.cl2.integration.application.command.ReportFlowExecutionCommand;
import com.cl2.integration.application.command.ReportFlowExecutionStepCommand;
import com.cl2.integration.application.exception.FlowExecutionInvalidException;
import com.cl2.integration.application.exception.FlowExecutionNotFoundException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowExecution;
import com.cl2.integration.domain.model.FlowExecutionStep;
import com.cl2.integration.domain.port.FlowExecutionRepository;
import com.cl2.integration.domain.port.FlowRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
            FlowExecution saved = flowExecutionRepository.save(tenantId, execution);
            if (!command.steps().isEmpty()) {
                flowExecutionRepository.saveSteps(saved.id(), toSteps(saved.id(), command.steps()));
            }
            return saved;
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

    @Transactional(readOnly = true)
    public FlowMetricsSummary rowMetrics(UUID tenantId, UUID flowId) {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        return flowExecutionRepository.executionMetricsForFlow(tenantId, flowId, since);
    }

    @Transactional(readOnly = true)
    public List<FlowExecution> listExecutions(UUID tenantId, UUID flowId) {
        flowRepository.findById(tenantId, flowId); // 404s if the flow itself doesn't exist/isn't this tenant's
        return flowExecutionRepository.findByFlow(tenantId, flowId);
    }

    @Transactional(readOnly = true)
    public FlowExecutionWithSteps getExecution(UUID tenantId, UUID flowId, UUID executionId) {
        FlowExecution execution = flowExecutionRepository.findById(tenantId, flowId, executionId)
                .orElseThrow(() -> new FlowExecutionNotFoundException(
                        "Flow execution " + executionId + " was not found for flow " + flowId));
        List<FlowExecutionStep> steps = flowExecutionRepository.findSteps(execution.id());
        return new FlowExecutionWithSteps(execution, steps);
    }

    private List<FlowExecutionStep> toSteps(UUID executionId, List<ReportFlowExecutionStepCommand> steps) {
        return java.util.stream.IntStream.range(0, steps.size())
                .mapToObj(i -> {
                    ReportFlowExecutionStepCommand s = steps.get(i);
                    return new FlowExecutionStep(UUID.randomUUID(), executionId, s.nodeId(), s.status(),
                            s.startedAt(), s.durationMs(), s.errorMessage(), i);
                })
                .toList();
    }

    public record FlowExecutionWithSteps(FlowExecution execution, List<FlowExecutionStep> steps) {
    }
}
