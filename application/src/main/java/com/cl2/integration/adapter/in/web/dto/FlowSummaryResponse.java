package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.FlowMetricsSummary;
import com.cl2.integration.application.FlowView;
import java.time.Instant;
import java.util.UUID;

public record FlowSummaryResponse(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        String triggerSummary,
        Integer activeVersionNumber,
        String status,
        int nodeCount,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long version,
        long execs24h,
        double errorRatePct,
        Long p95DurationMs) {

    public static FlowSummaryResponse from(FlowView view, FlowMetricsSummary rowMetrics) {
        return new FlowSummaryResponse(view.id(), view.tenantId(), view.code(), view.name(), view.triggerSummary(),
                view.activeVersionNumber(), view.status().name(), view.nodeCount(), view.archived(),
                view.createdAt(), view.updatedAt(), view.version(),
                rowMetrics.executions24h(), rowMetrics.errorRatePct(), rowMetrics.p95DurationMs());
    }
}
