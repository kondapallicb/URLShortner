package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record WorkflowNode(
        WorkflowStage stage,
        List<WorkflowStage> dependsOn,
        List<String> entryCriteria,
        List<String> exitCriteria,
        ApprovalGate approvalGate,
        boolean highImpact
) {
}
