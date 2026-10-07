package com.kondapallicb.urlshortener.observability;

import java.util.List;

public record ArchitectureOverview(
        String style,
        List<String> components,
        List<String> controlFlow,
        List<String> keyDecisions
) {
}
