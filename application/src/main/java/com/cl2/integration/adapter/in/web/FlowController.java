package com.cl2.integration.adapter.in.web;

import com.cl2.integration.adapter.in.web.dto.CreateFlowRequest;
import com.cl2.integration.adapter.in.web.dto.FlowExecutionDetailResponse;
import com.cl2.integration.adapter.in.web.dto.FlowExecutionResponse;
import com.cl2.integration.adapter.in.web.dto.FlowMetricsSummaryResponse;
import com.cl2.integration.adapter.in.web.dto.FlowResponse;
import com.cl2.integration.adapter.in.web.dto.FlowSummaryResponse;
import com.cl2.integration.adapter.in.web.dto.FlowVersionResponse;
import com.cl2.integration.adapter.in.web.dto.ReportFlowExecutionRequest;
import com.cl2.integration.adapter.in.web.dto.UpdateFlowDraftRequest;
import com.cl2.integration.application.FlowMetricsService;
import com.cl2.integration.application.FlowService;
import com.cl2.integration.application.command.CreateFlowCommand;
import com.cl2.integration.application.command.ReportFlowExecutionCommand;
import com.cl2.integration.application.command.ReportFlowExecutionStepCommand;
import com.cl2.integration.application.command.UpdateFlowDraftCommand;
import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flows")
public class FlowController {

    private final FlowService service;
    private final FlowMetricsService metricsService;
    private final ObjectMapper objectMapper;

    public FlowController(FlowService service, FlowMetricsService metricsService, ObjectMapper objectMapper) {
        this.service = service;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlowResponse create(@Valid @RequestBody CreateFlowRequest request) {
        return FlowResponse.from(service.create(TenantContext.requireTenantId(),
                new CreateFlowCommand(request.code(), request.name())), objectMapper);
    }

    @GetMapping
    public List<FlowSummaryResponse> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.list(TenantContext.requireTenantId(), activeOnly).stream()
                .map(FlowSummaryResponse::from)
                .toList();
    }

    @GetMapping("/metrics/summary")
    public FlowMetricsSummaryResponse metricsSummary() {
        return FlowMetricsSummaryResponse.from(metricsService.summarize(TenantContext.requireTenantId()));
    }

    @GetMapping("/{flowId}")
    public FlowResponse get(@PathVariable UUID flowId) {
        return FlowResponse.from(service.get(TenantContext.requireTenantId(), flowId), objectMapper);
    }

    @PutMapping("/{flowId}")
    public FlowResponse updateDraft(@PathVariable UUID flowId, @Valid @RequestBody UpdateFlowDraftRequest request) {
        String draftGraph = request.draftGraph() != null ? request.draftGraph().toString() : null;
        return FlowResponse.from(service.updateDraft(TenantContext.requireTenantId(), flowId,
                new UpdateFlowDraftCommand(request.name(), request.triggerSummary(), draftGraph,
                        request.expectedVersion())), objectMapper);
    }

    @GetMapping("/{flowId}/versions")
    public List<FlowVersionResponse> listVersions(@PathVariable UUID flowId) {
        return service.listVersions(TenantContext.requireTenantId(), flowId).stream()
                .map(view -> FlowVersionResponse.from(view, objectMapper))
                .toList();
    }

    @PostMapping("/{flowId}/versions/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public FlowVersionResponse publish(@PathVariable UUID flowId, Principal principal) {
        String publishedBy = principal != null ? principal.getName() : "unknown";
        return FlowVersionResponse.from(service.publish(TenantContext.requireTenantId(), flowId, publishedBy),
                objectMapper);
    }

    @PostMapping("/{flowId}/versions/{versionNumber}/rollback")
    public FlowVersionResponse rollback(@PathVariable UUID flowId, @PathVariable int versionNumber) {
        return FlowVersionResponse.from(service.rollback(TenantContext.requireTenantId(), flowId, versionNumber),
                objectMapper);
    }

    @PostMapping("/{flowId}/executions")
    @ResponseStatus(HttpStatus.CREATED)
    public FlowExecutionResponse reportExecution(@PathVariable UUID flowId,
                                                  @Valid @RequestBody ReportFlowExecutionRequest request) {
        List<ReportFlowExecutionStepCommand> steps = request.steps() == null ? List.of()
                : request.steps().stream()
                        .map(step -> new ReportFlowExecutionStepCommand(step.nodeId(), step.status(),
                                step.startedAt(), step.durationMs(), step.errorMessage()))
                        .toList();
        ReportFlowExecutionCommand command = new ReportFlowExecutionCommand(request.flowVersionNumber(),
                request.status(), request.startedAt(), request.finishedAt(), request.errorMessage(), steps);
        return FlowExecutionResponse.from(metricsService.report(TenantContext.requireTenantId(), flowId, command));
    }

    @GetMapping("/{flowId}/executions")
    public List<FlowExecutionResponse> listExecutions(@PathVariable UUID flowId) {
        return metricsService.listExecutions(TenantContext.requireTenantId(), flowId).stream()
                .map(FlowExecutionResponse::from)
                .toList();
    }

    @GetMapping("/{flowId}/executions/{executionId}")
    public FlowExecutionDetailResponse getExecution(@PathVariable UUID flowId, @PathVariable UUID executionId) {
        return FlowExecutionDetailResponse.from(
                metricsService.getExecution(TenantContext.requireTenantId(), flowId, executionId));
    }

    @DeleteMapping("/{flowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID flowId) {
        service.archive(TenantContext.requireTenantId(), flowId);
    }
}
