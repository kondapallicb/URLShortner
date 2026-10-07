package com.kondapallicb.urlshortener.orchestration;

import java.time.Instant;

public record DecisionRecord(
        Instant decidedAt,
        WorkflowStage stage,
        String decision,
        String rationale,
        ApprovalAudit approval
) {
    public record ApprovalAudit(String gate, String operator, String evidenceHash) { }
    public DecisionRecord(Instant decidedAt, WorkflowStage stage, String decision, String rationale) {
        this(decidedAt, stage, decision, rationale, null);
    }
}
