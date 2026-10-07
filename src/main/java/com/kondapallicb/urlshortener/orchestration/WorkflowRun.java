package com.kondapallicb.urlshortener.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowRun(
        String runId,
        WorkflowScenario scenario,
        String requirement,
        ExecutionState state,
        WorkflowGraph graph,
        GovernancePolicy policy,
        Map<WorkflowStage, WorkflowNodeRun> nodeRuns,
        List<DecisionRecord> decisions,
        List<ApprovalGate> pendingApprovals,
        OrchestrationMetrics metrics,
        Instant startedAt,
        Instant updatedAt
) {
}
