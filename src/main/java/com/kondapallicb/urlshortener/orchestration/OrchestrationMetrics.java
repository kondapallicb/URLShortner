package com.kondapallicb.urlshortener.orchestration;

public record OrchestrationMetrics(
        int completedNodes,
        int failedNodes,
        int retryCount,
        int rollbackCount,
        long endToEndLatencyMillis,
        double successRate
) {
}
