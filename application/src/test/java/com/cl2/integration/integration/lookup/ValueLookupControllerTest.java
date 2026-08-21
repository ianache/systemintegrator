package com.cl2.integration.integration.lookup;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cl2.integration.integration.lookup.adapter.in.web.ValueLookupController;
import com.cl2.integration.integration.lookup.application.ValueLookupService;
import com.cl2.integration.integration.lookup.domain.ValueLookup;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ValueLookupController.class)
class ValueLookupControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID LOOKUP_ID = UUID.fromString("7b4fe930-a3ce-43c1-9297-ff7a3c60f80c");
    private static final String BASE_PATH = "/api/v1/lookups";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ValueLookupService service;

    @Test
    void createsOrUpsertsSingleLookup() throws Exception {
        ValueLookup lookup = ValueLookup.rehydrate(
                LOOKUP_ID, TENANT_ID, "sigo", "TIPO_VEHICULO", "AUTO", "1", "Automovil", true,
                Instant.parse("2026-08-20T12:00:00Z"), Instant.parse("2026-08-20T12:00:00Z")
        );
        given(service.save(eq(TENANT_ID), any())).willReturn(lookup);

        mockMvc.perform(post(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalSource": "sigo",
                                  "catalogCode": "TIPO_VEHICULO",
                                  "sourceValue": "AUTO",
                                  "targetValue": "1",
                                  "description": "Automovil",
                                  "active": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(LOOKUP_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.externalSource").value("sigo"))
                .andExpect(jsonPath("$.catalogCode").value("TIPO_VEHICULO"))
                .andExpect(jsonPath("$.sourceValue").value("AUTO"))
                .andExpect(jsonPath("$.targetValue").value("1"))
                .andExpect(jsonPath("$.active").value(true));

        then(service).should().save(eq(TENANT_ID), any());
    }

    @Test
    void batchUpsertsLookups() throws Exception {
        ValueLookup lookup1 = ValueLookup.rehydrate(
                LOOKUP_ID, TENANT_ID, "sigo", "TIPO_VEHICULO", "AUTO", "1", "Automovil", true,
                Instant.parse("2026-08-20T12:00:00Z"), Instant.parse("2026-08-20T12:00:00Z")
        );
        ValueLookup lookup2 = ValueLookup.rehydrate(
                UUID.randomUUID(), TENANT_ID, "sigo", "TIPO_VEHICULO", "MOTO", "2", "Motocicleta", true,
                Instant.parse("2026-08-20T12:00:00Z"), Instant.parse("2026-08-20T12:00:00Z")
        );
        given(service.saveBatch(eq(TENANT_ID), any())).willReturn(List.of(lookup1, lookup2));

        mockMvc.perform(post(BASE_PATH + "/batch")
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "externalSource": "sigo",
                                    "catalogCode": "TIPO_VEHICULO",
                                    "sourceValue": "AUTO",
                                    "targetValue": "1",
                                    "description": "Automovil",
                                    "active": true
                                  },
                                  {
                                    "externalSource": "sigo",
                                    "catalogCode": "TIPO_VEHICULO",
                                    "sourceValue": "MOTO",
                                    "targetValue": "2",
                                    "description": "Motocicleta",
                                    "active": true
                                  }
                                ]
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sourceValue").value("AUTO"))
                .andExpect(jsonPath("$[1].sourceValue").value("MOTO"));

        then(service).should().saveBatch(eq(TENANT_ID), any());
    }

    @Test
    void listsLookupsByCatalog() throws Exception {
        ValueLookup lookup = ValueLookup.rehydrate(
                LOOKUP_ID, TENANT_ID, "sigo", "TIPO_VEHICULO", "AUTO", "1", "Automovil", true,
                Instant.parse("2026-08-20T12:00:00Z"), Instant.parse("2026-08-20T12:00:00Z")
        );
        given(service.findAll(TENANT_ID, "sigo", "TIPO_VEHICULO")).willReturn(List.of(lookup));

        mockMvc.perform(get(BASE_PATH)
                        .header("X-Tenant-ID", TENANT_ID)
                        .param("externalSource", "sigo")
                        .param("catalogCode", "TIPO_VEHICULO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].catalogCode").value("TIPO_VEHICULO"))
                .andExpect(jsonPath("$[0].targetValue").value("1"));

        then(service).should().findAll(TENANT_ID, "sigo", "TIPO_VEHICULO");
    }

    @Test
    void deletesLookupById() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/{id}", LOOKUP_ID)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNoContent());

        then(service).should().deleteById(TENANT_ID, LOOKUP_ID);
    }
}
