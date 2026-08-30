package com.cl2.integration.adapter.in.web.dto;

import com.cl2.integration.application.FlowMetricsSummary;

public record FlowMetricsSummaryResponse(
        long publishedFlowCount,
        long executions24h,
        double errorRatePct,
        Long p95DurationMs) {

    public static FlowMetricsSummaryResponse from(FlowMetricsSummary summary) {
        return new FlowMetricsSummaryResponse(summary.publishedFlowCount(), summary.executions24h(),
                summary.errorRatePct(), summary.p95DurationMs());
    }
}
