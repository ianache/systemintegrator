package com.cl2.integration.application;

public record FlowMetricsSummary(
        long publishedFlowCount,
        long executions24h,
        double errorRatePct,
        Long p95DurationMs,
        Long p50DurationMs,
        Long lastRunStepCount,
        long failedStepCount) {

    public FlowMetricsSummary withPublishedFlowCount(long publishedFlowCount) {
        return new FlowMetricsSummary(publishedFlowCount, executions24h, errorRatePct, p95DurationMs, p50DurationMs,
                lastRunStepCount, failedStepCount);
    }
}
