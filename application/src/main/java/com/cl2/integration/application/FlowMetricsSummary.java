package com.cl2.integration.application;

public record FlowMetricsSummary(
        long publishedFlowCount,
        long executions24h,
        double errorRatePct,
        Long p95DurationMs) {

    public FlowMetricsSummary withPublishedFlowCount(long publishedFlowCount) {
        return new FlowMetricsSummary(publishedFlowCount, executions24h, errorRatePct, p95DurationMs);
    }
}
