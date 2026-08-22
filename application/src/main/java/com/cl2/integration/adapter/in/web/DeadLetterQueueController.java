package com.cl2.integration.adapter.in.web;

import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.inbox.DeadLetterQueueReplayService;
import com.cl2.integration.integration.inbox.DeadLetterQueueReplayService.ReplaySummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inbox/dlq")
public class DeadLetterQueueController {

    private final DeadLetterQueueReplayService replayService;

    public DeadLetterQueueController(DeadLetterQueueReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/replay")
    public ResponseEntity<ReplaySummary> replay() {
        UUID tenantId = TenantContext.requireTenantId();
        ReplaySummary summary = replayService.replay(tenantId);
        return ResponseEntity.ok(summary);
    }
}
