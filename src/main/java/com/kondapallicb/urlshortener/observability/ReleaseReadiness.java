package com.kondapallicb.urlshortener.observability;

import java.util.List;

public record ReleaseReadiness(
        String status,
        List<String> completed,
        List<String> requiredBeforeProduction,
        List<String> rollbackPlan
) {
}
