package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record WorkflowGraph(List<WorkflowNode> nodes, List<EngineeringPlan.Task> tasks) {
    public WorkflowGraph(List<WorkflowNode> nodes) { this(nodes, List.of()); }

    public static WorkflowGraph fromPlan(EngineeringPlan plan, GovernancePolicy policy) {
        var nodes = new java.util.ArrayList<WorkflowNode>();
        var lifecycle = List.of(
            new WorkflowNode(WorkflowStage.REQUIREMENTS, List.of(), List.of("Original requirement"), List.of("READY interpretation"), null, false),
            new WorkflowNode(WorkflowStage.DECOMPOSITION, List.of(WorkflowStage.REQUIREMENTS), List.of("Repository analyzed"), List.of("Validated task DAG"), null, false),
            new WorkflowNode(WorkflowStage.ARCHITECTURE_DESIGN, List.of(WorkflowStage.DECOMPOSITION), List.of("Current input hashes"), List.of("Exact plan approval"),
                new ApprovalGate("architecture-review", "Approve requirement-specific task scopes and criteria", true, false, null, null), true),
            new WorkflowNode(WorkflowStage.IMPLEMENTATION, List.of(WorkflowStage.ARCHITECTURE_DESIGN), List.of("Approved plan"), List.of("Governed operations and compiled production"), null, false),
            new WorkflowNode(WorkflowStage.TESTING, List.of(WorkflowStage.IMPLEMENTATION), List.of("Compiled outputs"), List.of("Every criterion executed and covered"), null, false),
            new WorkflowNode(WorkflowStage.DOCUMENTATION, List.of(WorkflowStage.IMPLEMENTATION), List.of("Generated runbook"), List.of("Hashed documentation artifact"), null, false),
            new WorkflowNode(WorkflowStage.RELEASE_READINESS, List.of(WorkflowStage.TESTING, WorkflowStage.DOCUMENTATION), List.of("Current evidence"), List.of("Exact outcome approval"),
                new ApprovalGate("release-readiness", "Accept validated engineering outcome", true, false, null, null), true));
        for (WorkflowNode node : lifecycle) {
            if (node.stage() == WorkflowStage.RELEASE_READINESS && policy.requireSecurityReview()) {
                nodes.add(new WorkflowNode(WorkflowStage.SECURITY_REVIEW, List.of(WorkflowStage.TESTING),
                        List.of("Generated artifacts satisfy security checks"), List.of("Authenticated review of exact policy and execution evidence"),
                        new ApprovalGate("security-review", "Independent security approval required by policy", true, false, null, null), true));
                nodes.add(new WorkflowNode(node.stage(), List.of(WorkflowStage.SECURITY_REVIEW, WorkflowStage.DOCUMENTATION),
                        node.entryCriteria(), node.exitCriteria(), node.approvalGate(), node.highImpact()));
            } else nodes.add(node);
        }
        return new WorkflowGraph(List.copyOf(nodes), plan.tasks());
    }

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
