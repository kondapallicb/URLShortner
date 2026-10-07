package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record WorkflowGraph(List<WorkflowNode> nodes) {

    public static WorkflowGraph defaultGraph() {
        WorkflowNode requirements = new WorkflowNode(
                WorkflowStage.REQUIREMENTS,
                List.of(),
                List.of("Requirement captured", "Ambiguity reviewed"),
                List.of("Normalized engineering problem"),
                null,
                true
        );
        WorkflowNode decomposition = new WorkflowNode(
                WorkflowStage.DECOMPOSITION,
                List.of(WorkflowStage.REQUIREMENTS),
                List.of("Requirement normalized"),
                List.of("Sequenced tasks", "Dependency map"),
                null,
                true
        );
        WorkflowNode design = new WorkflowNode(
                WorkflowStage.ARCHITECTURE_DESIGN,
                List.of(WorkflowStage.DECOMPOSITION),
                List.of("Impacted modules identified", "Policy guardrails applied"),
                List.of("Architecture decisions", "API contracts"),
                new ApprovalGate("architecture-review", "Architecture affects public API and governance model", true, false, null, null),
                true
        );
        WorkflowNode implementation = new WorkflowNode(
                WorkflowStage.IMPLEMENTATION,
                List.of(WorkflowStage.ARCHITECTURE_DESIGN),
                List.of("Architecture approval complete"),
                List.of("Production code", "Migration notes"),
                null,
                false
        );
        WorkflowNode testing = new WorkflowNode(
                WorkflowStage.TESTING,
                List.of(WorkflowStage.IMPLEMENTATION),
                List.of("Code compiles", "Tests cover core behavior"),
                List.of("Unit tests", "Integration tests", "Risk checks"),
                null,
                false
        );
        WorkflowNode documentation = new WorkflowNode(
                WorkflowStage.DOCUMENTATION,
                List.of(WorkflowStage.IMPLEMENTATION),
                List.of("Implementation summary available"),
                List.of("README updates", "Trade-offs", "Runbook notes"),
                null,
                false
        );
        WorkflowNode release = new WorkflowNode(
                WorkflowStage.RELEASE_READINESS,
                List.of(WorkflowStage.TESTING, WorkflowStage.DOCUMENTATION),
                List.of("Validation passed", "Documentation complete"),
                List.of("Release summary", "Rollback plan"),
                new ApprovalGate("release-readiness", "Release readiness requires final human ownership", true, false, null, null),
                true
        );
        return new WorkflowGraph(List.of(requirements, decomposition, design, implementation, testing, documentation, release));
    }
}
