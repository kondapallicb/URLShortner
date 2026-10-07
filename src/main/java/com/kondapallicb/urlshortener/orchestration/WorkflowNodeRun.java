package com.kondapallicb.urlshortener.orchestration;

import java.time.Instant;
import java.util.List;

public record WorkflowNodeRun(
        WorkflowStage stage,
        ExecutionState state,
        int attempts,
        Instant startedAt,
        Instant completedAt,
        List<String> outputs,
        String failureReason
) {
    public static WorkflowNodeRun pending(WorkflowStage stage) {
        return new WorkflowNodeRun(stage, ExecutionState.PENDING, 0, null, null, List.of(), null);
    }

    public WorkflowNodeRun running(Instant now) {
        return new WorkflowNodeRun(stage, ExecutionState.RUNNING, attempts + 1, now, null, outputs, null);
    }

    public WorkflowNodeRun completed(Instant now, List<String> nodeOutputs) {
        return new WorkflowNodeRun(stage, ExecutionState.COMPLETED, attempts, startedAt, now, nodeOutputs, null);
    }

    public WorkflowNodeRun waitingForApproval(Instant now, List<String> nodeOutputs) {
        return new WorkflowNodeRun(stage, ExecutionState.WAITING_FOR_APPROVAL, attempts, startedAt, now, nodeOutputs, null);
    }
}
