package com.kondapallicb.urlshortener.api;

import com.kondapallicb.urlshortener.orchestration.WorkflowScenario;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StartWorkflowRequest(
        @NotNull WorkflowScenario scenario,
        String requirement,
        com.kondapallicb.urlshortener.orchestration.RequirementSpec specification
) {
    public StartWorkflowRequest(com.kondapallicb.urlshortener.orchestration.WorkflowScenario scenario, String requirement) {
        this(scenario, requirement, null);
    }
}
