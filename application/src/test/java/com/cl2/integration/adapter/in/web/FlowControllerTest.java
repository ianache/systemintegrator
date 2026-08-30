package com.cl2.integration.adapter.in.web;

import com.cl2.integration.application.FlowService;
import com.cl2.integration.application.FlowVersionView;
import com.cl2.integration.application.FlowView;
import com.cl2.integration.application.command.CreateFlowCommand;
import com.cl2.integration.application.command.UpdateFlowDraftCommand;
import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.application.exception.FlowNotPublishableException;
import com.cl2.integration.domain.model.FlowStatus;
import com.cl2.integration.domain.model.FlowVersionState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlowController.class)
class FlowControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID FLOW_ID = UUID.fromString("7b4fe930-a3ce-43c1-9297-ff7a3c60f80c");
    private static final String BASE_PATH = "/api/v1/flows";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FlowService service;

    @Test
    void createsAFlowForTheTenantFromTheHeader() throws Exception {
        given(service.create(eq(TENANT_ID), any(CreateFlowCommand.class))).willReturn(flowView());

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"flow/x","name":"X"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(FLOW_ID.toString()))
                .andExpect(jsonPath("$.code").value("flow/x"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        then(service).should().create(eq(TENANT_ID), any(CreateFlowCommand.class));
    }

    @Test
    void returns409WhenTheServiceReportsAConflict() throws Exception {
        given(service.create(eq(TENANT_ID), any(CreateFlowCommand.class)))
                .willThrow(new FlowConflictException("A flow already exists"));

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"flow/x","name":"X"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void listsFlowsForTheTenantFromTheHeader() throws Exception {
        FlowView withDraft = new FlowView(FLOW_ID, TENANT_ID, "flow/x", "X", "{\"nodes\":[]}", null, null,
                FlowStatus.DRAFT, 0, false, Instant.parse("2026-08-30T00:00:00Z"),
                Instant.parse("2026-08-30T00:00:00Z"), 0);
        given(service.list(eq(TENANT_ID), eq(true))).willReturn(List.of(withDraft));

        mockMvc.perform(get(BASE_PATH).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSizeOne()))
                .andExpect(jsonPath("$[0].draftGraph").doesNotExist());

        then(service).should().list(TENANT_ID, true);
    }

    @Test
    void getsAFlowByIdForTheTenant() throws Exception {
        given(service.get(TENANT_ID, FLOW_ID)).willReturn(flowView());

        mockMvc.perform(get(BASE_PATH + "/{flowId}", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FLOW_ID.toString()));
    }

    @Test
    void returns404WhenTheFlowIsNotFound() throws Exception {
        given(service.get(TENANT_ID, FLOW_ID)).willThrow(new FlowNotFoundException("not found"));

        mockMvc.perform(get(BASE_PATH + "/{flowId}", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesTheDraftForTheTenant() throws Exception {
        given(service.updateDraft(eq(TENANT_ID), eq(FLOW_ID), any(UpdateFlowDraftCommand.class)))
                .willReturn(flowView());

        mockMvc.perform(put(BASE_PATH + "/{flowId}", FLOW_ID)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X renamed","triggerSummary":"CRON */5","draftGraph":{"nodes":[]},"expectedVersion":0}
                                """))
                .andExpect(status().isOk());

        then(service).should().updateDraft(eq(TENANT_ID), eq(FLOW_ID), any(UpdateFlowDraftCommand.class));
    }

    @Test
    void listsVersionsForAFlow() throws Exception {
        given(service.listVersions(TENANT_ID, FLOW_ID)).willReturn(List.of(flowVersionView()));

        mockMvc.perform(get(BASE_PATH + "/{flowId}/versions", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNumber").value(1));
    }

    @Test
    void publishesTheCurrentDraft() throws Exception {
        given(service.publish(eq(TENANT_ID), eq(FLOW_ID), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(flowVersionView());

        mockMvc.perform(post(BASE_PATH + "/{flowId}/versions/publish", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    @Test
    void returns422WhenTheDraftIsEmptyOnPublish() throws Exception {
        given(service.publish(eq(TENANT_ID), eq(FLOW_ID), org.mockito.ArgumentMatchers.anyString()))
                .willThrow(new FlowNotPublishableException("Cannot publish a flow with an empty draft graph"));

        mockMvc.perform(post(BASE_PATH + "/{flowId}/versions/publish", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rollsBackToAnOlderVersion() throws Exception {
        given(service.rollback(TENANT_ID, FLOW_ID, 1)).willReturn(flowVersionView());

        mockMvc.perform(post(BASE_PATH + "/{flowId}/versions/{versionNumber}/rollback", FLOW_ID, 1)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(1));
    }

    @Test
    void archivesAFlow() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/{flowId}", FLOW_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNoContent());

        then(service).should().archive(TENANT_ID, FLOW_ID);
    }

    private static <T> org.hamcrest.Matcher<java.util.Collection<? extends T>> hasSizeOne() {
        return org.hamcrest.Matchers.hasSize(1);
    }

    private static FlowView flowView() {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        return new FlowView(FLOW_ID, TENANT_ID, "flow/x", "X", null, null, null, FlowStatus.DRAFT, 0, false, now, now, 0);
    }

    private static FlowVersionView flowVersionView() {
        return new FlowVersionView(UUID.randomUUID(), FLOW_ID, 1, "{}", FlowVersionState.ACTIVE, "user@tenant",
                Instant.parse("2026-08-30T00:00:00Z"));
    }
}
