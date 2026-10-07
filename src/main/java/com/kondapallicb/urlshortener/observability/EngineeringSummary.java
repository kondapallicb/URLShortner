package com.kondapallicb.urlshortener.observability;

import java.util.List;

public record EngineeringSummary(
        String objective,
        ArchitectureOverview architecture,
        ReleaseReadiness releaseReadiness,
        List<String> validation,
        List<String> risks,
        List<String> tradeOffs,
        List<String> limitations,
        List<String> nextSteps
) {
}
