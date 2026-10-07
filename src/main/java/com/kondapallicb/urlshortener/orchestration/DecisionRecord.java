package com.kondapallicb.urlshortener.orchestration;

import java.time.Instant;

public record DecisionRecord(
        Instant decidedAt,
        WorkflowStage stage,
        String decision,
        String rationale
) {
}
