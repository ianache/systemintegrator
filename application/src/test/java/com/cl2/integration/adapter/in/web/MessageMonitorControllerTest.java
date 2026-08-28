package com.cl2.integration.adapter.in.web;

import com.cl2.integration.integration.monitor.MessageDetail;
import com.cl2.integration.integration.monitor.MessageMonitorService;
import com.cl2.integration.integration.monitor.MessageNotFoundException;
import com.cl2.integration.integration.monitor.MessageSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MessageMonitorController.class)
class MessageMonitorControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID MESSAGE_ID = UUID.fromString("7b4fe930-a3ce-43c1-9297-ff7a3c60f80c");
    private static final String BASE_PATH = "/api/v1/messages";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessageMonitorService service;

    @Test
    void listsMessagesForTheTenantFromTheHeader() throws Exception {
        given(service.list(TENANT_ID, "DLQ")).willReturn(List.of(
                new MessageSummary(MESSAGE_ID, "INBOUND", "units.upserted", "units", "DLQ", 1, "boom", Instant.parse("2026-08-20T10:00:00Z"))
        ));

        mockMvc.perform(get(BASE_PATH).param("status", "DLQ").header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("DLQ"));
    }

    @Test
    void returnsMessageDetail() throws Exception {
        given(service.find(TENANT_ID, "INBOUND", MESSAGE_ID)).willReturn(
                new MessageDetail(MESSAGE_ID, "INBOUND", "units.upserted", "units", "DLQ", 1, "boom", Instant.parse("2026-08-20T10:00:00Z"), "{}")
        );

        mockMvc.perform(get(BASE_PATH + "/INBOUND/{id}", MESSAGE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("{}"));
    }

    @Test
    void returns404WhenMessageIsMissing() throws Exception {
        given(service.find(TENANT_ID, "INBOUND", MESSAGE_ID)).willThrow(new MessageNotFoundException("not found"));

        mockMvc.perform(get(BASE_PATH + "/INBOUND/{id}", MESSAGE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void retriesAMessage() throws Exception {
        given(service.retry(TENANT_ID, "OUTBOUND", MESSAGE_ID)).willReturn(
                new MessageDetail(MESSAGE_ID, "OUTBOUND", "vehicle.created", "vehicle", "PENDING", 0, null, Instant.parse("2026-08-20T10:00:00Z"), "{}")
        );

        mockMvc.perform(post(BASE_PATH + "/OUTBOUND/{id}/retry", MESSAGE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        then(service).should().retry(TENANT_ID, "OUTBOUND", MESSAGE_ID);
    }

    @Test
    void movesAMessageToDlq() throws Exception {
        given(service.moveToDlq(TENANT_ID, "OUTBOUND", MESSAGE_ID)).willReturn(
                new MessageDetail(MESSAGE_ID, "OUTBOUND", "vehicle.created", "vehicle", "DLQ", 5, "boom", Instant.parse("2026-08-20T10:00:00Z"), "{}")
        );

        mockMvc.perform(post(BASE_PATH + "/OUTBOUND/{id}/dlq", MESSAGE_ID).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DLQ"));
    }
}
