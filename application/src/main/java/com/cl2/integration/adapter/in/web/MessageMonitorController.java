package com.cl2.integration.adapter.in.web;

import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.monitor.MessageDetail;
import com.cl2.integration.integration.monitor.MessageMonitorService;
import com.cl2.integration.integration.monitor.MessageSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageMonitorController {

    private final MessageMonitorService service;

    public MessageMonitorController(MessageMonitorService service) {
        this.service = service;
    }

    @GetMapping
    public List<MessageSummary> list(@RequestParam(required = false, defaultValue = "ALL") String status) {
        UUID tenantId = TenantContext.requireTenantId();
        return service.list(tenantId, status);
    }

    @GetMapping("/{direction}/{id}")
    public MessageDetail find(@PathVariable String direction, @PathVariable UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        return service.find(tenantId, direction, id);
    }

    @PostMapping("/{direction}/{id}/retry")
    public MessageDetail retry(@PathVariable String direction, @PathVariable UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        return service.retry(tenantId, direction, id);
    }

    @PostMapping("/{direction}/{id}/dlq")
    public MessageDetail moveToDlq(@PathVariable String direction, @PathVariable UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        return service.moveToDlq(tenantId, direction, id);
    }
}
