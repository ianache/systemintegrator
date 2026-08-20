package com.cl2.integration.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
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

import com.cl2.integration.application.IntegrationProfileService;
import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.application.command.UpdateIntegrationProfileCommand;
import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IntegrationProfileController.class)
class IntegrationProfileControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("b129386f-2ec1-4f2a-8d09-f2aed3b154c2");
    private static final UUID PROFILE_ID = UUID.fromString("7b4fe930-a3ce-43c1-9297-ff7a3c60f80c");
    private static final String BASE_PATH = "/api/v1/integration-profiles";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IntegrationProfileService service;

    @MockitoBean
    private com.cl2.integration.integration.sync.IntegrationSyncService syncService;

    @Test
    void triggersSyncForAProfileForTheTenantFromTheHeader() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/{profileId}/sync", PROFILE_ID)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.profileId").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.status").value("TRIGGERED"))
                .andExpect(jsonPath("$.triggeredAt").isNotEmpty());

        then(syncService).should().triggerSync(TENANT_ID, PROFILE_ID);
    }

    @Test
    void createsAProfileForTheTenantFromTheHeader() throws Exception {
        given(service.create(eq(TENANT_ID), any(CreateIntegrationProfileCommand.class))).willReturn(profileView(TENANT_ID));

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM","tenantId":"%s"}
                                """.formatted(OTHER_TENANT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.syncDirection").value("INBOUND"));

        then(service).should().create(eq(TENANT_ID), any(CreateIntegrationProfileCommand.class));
    }

    @Test
    void createsAndReturnsAnExtendedProfile() throws Exception {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-vehicle-http", "https://sigo.test/api", "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}", null, null, "{\"maxAttempts\":3,\"initialBackoffMs\":100}",
                "{\"requestsPerSecond\":10}", null);
        IntegrationProfileView view = new IntegrationProfileView(PROFILE_ID, TENANT_ID, "orders", "erp",
                SyncDirection.INBOUND, SourceOfTruth.PLATFORM, config, true, Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T12:00:00Z"), 0);

        given(service.create(eq(TENANT_ID), any(CreateIntegrationProfileCommand.class))).willReturn(view);

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":"orders","externalSource":"erp",
                                 "syncDirection":"INBOUND","sourceOfTruth":"PLATFORM",
                                 "protocol":"REST","connector":"sigo",
                                 "adapter":"sigo-vehicle-http","endpoint":"https://sigo.test/api",
                                 "credentialRef":"secret/sigo/orders",
                                 "mapping":{"vin":"vehicle.vin"},
                                 "retryPolicy":{"maxAttempts":3,"initialBackoffMs":100},
                                 "rateLimitPolicy":{"requestsPerSecond":10}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configuration.protocol").value("REST"))
                .andExpect(jsonPath("$.configuration.credentialRef").value("secret/sigo/orders"))
                .andExpect(jsonPath("$.configuration.mapping.vin").value("vehicle.vin"));
    }

    @Test
    void acceptsALegacyProfileWithoutConfiguration() throws Exception {
        given(service.create(eq(TENANT_ID), any(CreateIntegrationProfileCommand.class))).willReturn(profileView(TENANT_ID));

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":"orders","externalSource":"erp",
                                 "syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configuration").doesNotExist());
    }

    @Test
    void rejectsMalformedConfigurationJson() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":"orders","externalSource":"erp",
                                 "syncDirection":"INBOUND","sourceOfTruth":"PLATFORM",
                                 "mapping":{"vin":}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsARequestWithoutATenantHeaderBeforeTheController() throws Exception {
        mockMvc.perform(get(BASE_PATH).header("X-Correlation-ID", "missing-tenant-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TENANT_HEADER_MISSING"))
                .andExpect(jsonPath("$.correlationId").value("missing-tenant-request"));

        then(service).shouldHaveNoInteractions();
    }

    @Test
    void rejectsARequestWithAMalformedTenantHeaderBeforeTheController() throws Exception {
        mockMvc.perform(get(BASE_PATH)
                        .header("X-Tenant-ID", "not-a-uuid")
                        .header("X-Correlation-ID", "malformed-tenant-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TENANT_HEADER_MALFORMED"))
                .andExpect(jsonPath("$.correlationId").value("malformed-tenant-request"));

        then(service).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAnInvalidRequestBodyWithAProblemDetail() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":" ","externalSource":"","syncDirection":null,"sourceOfTruth":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void listsProfilesAndPassesTheActiveOnlyFilter() throws Exception {
        given(service.list(TENANT_ID, false)).willReturn(List.of(profileView(TENANT_ID)));

        mockMvc.perform(get(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .param("activeOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$[0].active").value(true));

        then(service).should().list(TENANT_ID, false);
    }

    @Test
    void getsAProfileForTheTenantFromTheHeader() throws Exception {
        given(service.get(TENANT_ID, PROFILE_ID)).willReturn(profileView(TENANT_ID));

        mockMvc.perform(get(BASE_PATH + "/{profileId}", PROFILE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDomain").value("orders"));

        then(service).should().get(TENANT_ID, PROFILE_ID);
    }

    @Test
    void updatesAProfileForTheTenantFromTheHeader() throws Exception {
        IntegrationProfileView updated = new IntegrationProfileView(PROFILE_ID, TENANT_ID, "catalog", "crm",
                SyncDirection.OUTBOUND, SourceOfTruth.EXTERNAL, null, true, Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T12:01:00Z"), 1);
        given(service.update(eq(TENANT_ID), eq(PROFILE_ID), any(UpdateIntegrationProfileCommand.class))).willReturn(updated);

        mockMvc.perform(put(BASE_PATH + "/{profileId}", PROFILE_ID)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":"catalog","externalSource":"crm","syncDirection":"OUTBOUND","sourceOfTruth":"EXTERNAL","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDomain").value("catalog"))
                .andExpect(jsonPath("$.version").value(1));

        then(service).should().update(eq(TENANT_ID), eq(PROFILE_ID), any(UpdateIntegrationProfileCommand.class));
    }

    @Test
    void rejectsAnUpdateWithoutAnExpectedVersion() throws Exception {
        mockMvc.perform(put(BASE_PATH + "/{profileId}", PROFILE_ID)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":"catalog","externalSource":"crm","syncDirection":"OUTBOUND","sourceOfTruth":"EXTERNAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        then(service).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAnUpdateWithANegativeExpectedVersion() throws Exception {
        mockMvc.perform(put(BASE_PATH + "/{profileId}", PROFILE_ID)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":"catalog","externalSource":"crm","syncDirection":"OUTBOUND","sourceOfTruth":"EXTERNAL","expectedVersion":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        then(service).shouldHaveNoInteractions();
    }

    @Test
    void logicallyDeletesAProfileForTheTenantFromTheHeader() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/{profileId}", PROFILE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNoContent());

        then(service).should().deactivate(TENANT_ID, PROFILE_ID);
    }

    @Test
    void mapsAMissingProfileToNotFound() throws Exception {
        given(service.get(TENANT_ID, PROFILE_ID)).willThrow(new IntegrationProfileNotFoundException("Integration profile was not found"));

        mockMvc.perform(get(BASE_PATH + "/{profileId}", PROFILE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INTEGRATION_PROFILE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void mapsADuplicateActiveProfileToConflict() throws Exception {
        given(service.create(eq(TENANT_ID), any(CreateIntegrationProfileCommand.class)))
                .willThrow(new IntegrationProfileConflictException("An active integration profile already exists for this domain and source"));

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INTEGRATION_PROFILE_CONFLICT"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void returnsNotFoundWhenAnotherTenantRequestsAProfile() throws Exception {
        given(service.get(OTHER_TENANT_ID, PROFILE_ID))
                .willThrow(new IntegrationProfileNotFoundException("Integration profile was not found"));

        mockMvc.perform(get(BASE_PATH + "/{profileId}", PROFILE_ID).header("X-Tenant-ID", OTHER_TENANT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INTEGRATION_PROFILE_NOT_FOUND"));

        then(service).should().get(OTHER_TENANT_ID, PROFILE_ID);
    }

    private IntegrationProfileView profileView(UUID tenantId) {
        return new IntegrationProfileView(PROFILE_ID, tenantId, "orders", "erp", SyncDirection.INBOUND,
                SourceOfTruth.PLATFORM, null, true, Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T12:00:00Z"), 0);
    }
}
