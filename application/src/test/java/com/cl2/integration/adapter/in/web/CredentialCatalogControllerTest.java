package com.cl2.integration.adapter.in.web;

import com.cl2.integration.integration.credential.CredentialCatalogService;
import com.cl2.integration.integration.credential.CredentialSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CredentialCatalogController.class)
class CredentialCatalogControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CredentialCatalogService service;

    @Test
    void listsCredentialsForTheTenantFromTheHeader() throws Exception {
        given(service.list(TENANT_ID)).willReturn(List.of(
                new CredentialSummary("secret/cl2/cred", "BEARER", List.of("units · comsatel"), null, "VIGENTE")
        ));

        mockMvc.perform(get("/api/v1/credentials").header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ref").value("secret/cl2/cred"))
                .andExpect(jsonPath("$[0].state").value("VIGENTE"));
    }
}
