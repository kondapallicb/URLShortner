package com.kondapallicb.urlshortener.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowRun(
        String runId,
        WorkflowScenario scenario,
        String requirement,
        ScenarioDemonstration demonstration,
        ExecutionState state,
        WorkflowGraph graph,
        GovernancePolicy policy,
        Map<WorkflowStage, WorkflowNodeRun> nodeRuns,
        List<DecisionRecord> decisions,
        List<ApprovalGate> pendingApprovals,
        OrchestrationMetrics metrics,
        Instant startedAt,
        Instant updatedAt,
        long version
) {
    public WorkflowRun(String runId, WorkflowScenario scenario, String requirement, ScenarioDemonstration demonstration,
        ExecutionState state, WorkflowGraph graph, GovernancePolicy policy, Map<WorkflowStage, WorkflowNodeRun> nodeRuns,
        List<DecisionRecord> decisions, List<ApprovalGate> pendingApprovals, OrchestrationMetrics metrics, Instant startedAt, Instant updatedAt) {
        this(runId, scenario, requirement, demonstration, state, graph, policy, nodeRuns, decisions, pendingApprovals, metrics, startedAt, updatedAt, 0);
    }
    public WorkflowRun withVersion(long value) {
        return new WorkflowRun(runId, scenario, requirement, demonstration, state, graph, policy, nodeRuns,
            decisions, pendingApprovals, metrics, startedAt, updatedAt, value);
    }
}
