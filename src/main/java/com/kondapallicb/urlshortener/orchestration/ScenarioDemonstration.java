package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record ScenarioDemonstration(
        WorkflowScenario scenario,
        String title,
        String requirement,
        List<String> ambiguityNotes,
        List<String> decomposition,
        List<String> orchestrationPath,
        List<String> validationPlan,
        List<String> approvalCheckpoints,
        List<String> expectedArtifacts
) {
}
