package com.kondapallicb.urlshortener.orchestration;

public record ApprovalGate(
        String name,
        String reason,
        boolean required,
        boolean approved,
        String approvedBy,
        String comment
) {
    public ApprovalGate approve(String approver, String approvalComment) {
        return new ApprovalGate(name, reason, required, true, approver, approvalComment);
    }
}
