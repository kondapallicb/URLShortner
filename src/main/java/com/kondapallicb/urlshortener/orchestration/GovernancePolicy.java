package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record GovernancePolicy(
        int maxRetries,
        boolean requireSecurityReview,
        boolean requireHumanApprovalForHighImpactChanges,
        List<String> guardrails
) {
    public static GovernancePolicy defaultPolicy() {
        return new GovernancePolicy(
                2,
                true,
                true,
                List.of(
                        "No secrets or credentials in generated artifacts",
                        "All public APIs require validation and structured errors",
                        "High-impact changes wait for human approval",
                        "Rollback or safe-stop when validation cannot pass"
                )
        );
    }
}
