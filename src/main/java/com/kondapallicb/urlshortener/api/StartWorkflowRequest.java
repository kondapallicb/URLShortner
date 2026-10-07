package com.kondapallicb.urlshortener.api;

import com.kondapallicb.urlshortener.orchestration.WorkflowScenario;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StartWorkflowRequest(
        @NotNull WorkflowScenario scenario,
        @NotBlank String requirement
) {
}
