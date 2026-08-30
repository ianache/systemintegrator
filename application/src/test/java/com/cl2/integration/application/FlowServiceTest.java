package com.cl2.integration.application;

import com.cl2.integration.application.command.CreateFlowCommand;
import com.cl2.integration.application.command.UpdateFlowDraftCommand;
import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowStatus;
import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.port.FlowRepository;
import com.cl2.integration.domain.port.FlowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");

    private FakeFlowRepository flowRepository;
    private FakeFlowVersionRepository flowVersionRepository;
    private FlowService service;

    @BeforeEach
    void setUp() {
        flowRepository = new FakeFlowRepository();
        flowVersionRepository = new FakeFlowVersionRepository();
        service = new FlowService(flowRepository, flowVersionRepository, new ObjectMapper());
    }

    @Test
    void createsADraftFlowForTheSuppliedTenant() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        assertThat(created.tenantId()).isEqualTo(TENANT_ID);
        assertThat(created.code()).isEqualTo("flow/x");
        assertThat(created.status()).isEqualTo(FlowStatus.DRAFT);
        assertThat(created.nodeCount()).isZero();
    }

    @Test
    void rejectsCreatingADuplicateActiveCodeForTheSameTenant() {
        service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        assertThatThrownBy(() -> service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X again")))
                .isInstanceOf(FlowConflictException.class);
    }

    @Test
    void allowsTheSameCodeForDifferentTenants() {
        service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        FlowView createdForOtherTenant = service.create(OTHER_TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        assertThat(createdForOtherTenant.tenantId()).isEqualTo(OTHER_TENANT_ID);
    }

    @Test
    void listsFlowsScopedToTheTenant() {
        service.create(TENANT_ID, new CreateFlowCommand("flow/a", "A"));
        service.create(OTHER_TENANT_ID, new CreateFlowCommand("flow/b", "B"));

        List<FlowView> flows = service.list(TENANT_ID, true);

        assertThat(flows).extracting(FlowView::code).containsExactly("flow/a");
    }

    @Test
    void getsAFlowScopedToTheTenant() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        FlowView found = service.get(TENANT_ID, created.id());

        assertThat(found.id()).isEqualTo(created.id());
        assertThatThrownBy(() -> service.get(OTHER_TENANT_ID, created.id()))
                .isInstanceOf(FlowNotFoundException.class);
    }

    @Test
    void updateDraftReplacesNameTriggerAndGraphAndCountsNodes() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        FlowView updated = service.updateDraft(TENANT_ID, created.id(),
                new UpdateFlowDraftCommand("X renamed", "CRON */5", "{\"nodes\":[{\"id\":\"n1\"},{\"id\":\"n2\"}]}", 0));

        assertThat(updated.name()).isEqualTo("X renamed");
        assertThat(updated.triggerSummary()).isEqualTo("CRON */5");
        assertThat(updated.nodeCount()).isEqualTo(2);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void archiveMarksTheFlowObsolete() {
        FlowView created = service.create(TENANT_ID, new CreateFlowCommand("flow/x", "X"));

        service.archive(TENANT_ID, created.id());

        assertThat(service.get(TENANT_ID, created.id()).status()).isEqualTo(FlowStatus.OBSOLETE);
    }

    private static final class FakeFlowRepository implements FlowRepository {

        private final Map<UUID, Flow> flows = new HashMap<>();

        @Override
        public Flow save(UUID tenantId, Flow flow) {
            if (!tenantId.equals(flow.tenantId())) {
                throw new IllegalArgumentException("tenantId must match the flow tenantId");
            }
            flows.put(flow.id(), flow);
            return flow;
        }

        @Override
        public Flow findById(UUID tenantId, UUID id) {
            Flow flow = flows.get(id);
            if (flow == null || !flow.tenantId().equals(tenantId)) {
                throw new FlowNotFoundException("Flow was not found");
            }
            return flow;
        }

        @Override
        public List<Flow> findAll(UUID tenantId, boolean activeOnly) {
            return flows.values().stream()
                    .filter(flow -> flow.tenantId().equals(tenantId))
                    .filter(flow -> !activeOnly || !flow.archived())
                    .toList();
        }

        @Override
        public boolean existsActive(UUID tenantId, String code) {
            return flows.values().stream()
                    .anyMatch(flow -> flow.tenantId().equals(tenantId) && flow.code().equals(code) && !flow.archived());
        }
    }

    private static final class FakeFlowVersionRepository implements FlowVersionRepository {

        private final List<FlowVersion> versions = new ArrayList<>();

        @Override
        public FlowVersion save(UUID tenantId, FlowVersion version) {
            versions.removeIf(v -> v.id().equals(version.id()));
            versions.add(version);
            return version;
        }

        @Override
        public List<FlowVersion> findAllByFlowId(UUID tenantId, UUID flowId) {
            return versions.stream()
                    .filter(v -> v.tenantId().equals(tenantId) && v.flowId().equals(flowId))
                    .sorted((a, b) -> b.versionNumber() - a.versionNumber())
                    .toList();
        }

        @Override
        public Optional<FlowVersion> findByFlowIdAndVersionNumber(UUID tenantId, UUID flowId, int versionNumber) {
            return versions.stream()
                    .filter(v -> v.tenantId().equals(tenantId) && v.flowId().equals(flowId) && v.versionNumber() == versionNumber)
                    .findFirst();
        }

        @Override
        public Optional<FlowVersion> findActiveByFlowId(UUID tenantId, UUID flowId) {
            return versions.stream()
                    .filter(v -> v.tenantId().equals(tenantId) && v.flowId().equals(flowId)
                            && v.state() == com.cl2.integration.domain.model.FlowVersionState.ACTIVE)
                    .findFirst();
        }

        @Override
        public int nextVersionNumber(UUID tenantId, UUID flowId) {
            return (int) versions.stream()
                    .filter(v -> v.tenantId().equals(tenantId) && v.flowId().equals(flowId))
                    .count() + 1;
        }
    }
}
