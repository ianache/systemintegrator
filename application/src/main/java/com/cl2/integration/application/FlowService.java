package com.cl2.integration.application;

import com.cl2.integration.application.command.CreateFlowCommand;
import com.cl2.integration.application.command.UpdateFlowDraftCommand;
import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.port.FlowRepository;
import com.cl2.integration.domain.port.FlowVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowService {

    private final FlowRepository flowRepository;
    private final FlowVersionRepository flowVersionRepository;
    private final ObjectMapper objectMapper;

    public FlowService(FlowRepository flowRepository, FlowVersionRepository flowVersionRepository,
                        ObjectMapper objectMapper) {
        this.flowRepository = flowRepository;
        this.flowVersionRepository = flowVersionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FlowView create(UUID tenantId, CreateFlowCommand command) {
        if (flowRepository.existsActive(tenantId, command.code())) {
            throw new FlowConflictException("A flow already exists with this code for the tenant");
        }
        Flow flow = Flow.create(UUID.randomUUID(), tenantId, command.code(), command.name());
        return toView(flowRepository.save(tenantId, flow));
    }

    @Transactional(readOnly = true)
    public List<FlowView> list(UUID tenantId, boolean activeOnly) {
        return flowRepository.findAll(tenantId, activeOnly).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public FlowView get(UUID tenantId, UUID flowId) {
        return toView(flowRepository.findById(tenantId, flowId));
    }

    @Transactional
    public FlowView updateDraft(UUID tenantId, UUID flowId, UpdateFlowDraftCommand command) {
        Flow flow = flowRepository.findById(tenantId, flowId);
        Flow updated = flow.updateDraft(command.name(), command.triggerSummary(), command.draftGraph(),
                command.expectedVersion());
        return toView(flowRepository.save(tenantId, updated));
    }

    @Transactional
    public void archive(UUID tenantId, UUID flowId) {
        Flow flow = flowRepository.findById(tenantId, flowId);
        flowRepository.save(tenantId, flow.archive());
    }

    FlowView toView(Flow flow) {
        return new FlowView(flow.id(), flow.tenantId(), flow.code(), flow.name(), flow.draftGraph(),
                flow.triggerSummary(), flow.activeVersionNumber(), flow.status(), countNodes(flow.draftGraph()),
                flow.archived(), flow.createdAt(), flow.updatedAt(), flow.version());
    }

    private int countNodes(String draftGraph) {
        if (draftGraph == null || draftGraph.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(draftGraph);
            JsonNode nodes = root.get("nodes");
            return nodes != null && nodes.isArray() ? nodes.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
