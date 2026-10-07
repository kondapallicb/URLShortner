package com.kondapallicb.urlshortener.orchestration;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DefaultScenarioCatalog implements ScenarioCatalog {

    private final List<ScenarioDemonstration> scenarios = List.of(
            new ScenarioDemonstration(
                    WorkflowScenario.GREENFIELD,
                    "Greenfield: Custom Alias Support",
                    "Allow users to request a custom alias when creating a short URL.",
                    List.of("Alias ownership and collision handling must be made explicit."),
                    List.of(
                            "Add optional alias field to create request",
                            "Validate alias format, length, and reserved words",
                            "Reject collisions with structured 409 response",
                            "Persist alias as the slug when valid",
                            "Cover create, collision, redirect, and analytics behavior"
                    ),
                    List.of(
                            "requirements -> decomposition",
                            "architecture approval gate",
                            "implementation",
                            "testing and documentation in parallel",
                            "release readiness approval"
                    ),
                    List.of(
                            "Unit tests for alias validation",
                            "Controller tests for 201 and 409 responses",
                            "Regression test proving generated slugs still work"
                    ),
                    List.of("architecture-review", "release-readiness"),
                    List.of("API contract update", "domain validation", "tests", "README example")
            ),
            new ScenarioDemonstration(
                    WorkflowScenario.BROWNFIELD,
                    "Brownfield: Analytics Refactor",
                    "Refactor analytics from in-memory storage toward a repository port that can be backed by a database.",
                    List.of("Must preserve existing analytics API response shape."),
                    List.of(
                            "Identify current analytics write/read paths",
                            "Introduce analytics repository boundary",
                            "Move in-memory click storage behind the new port",
                            "Keep existing controller/service contract stable",
                            "Add regression tests before behavior changes"
                    ),
                    List.of(
                            "requirements -> impacted-module analysis",
                            "architecture approval gate for storage boundary",
                            "implementation with rollback point",
                            "regression testing and documentation in parallel",
                            "release readiness approval"
                    ),
                    List.of(
                            "Regression tests for click counting",
                            "Contract test for analytics JSON",
                            "Rollback plan to previous in-memory repository"
                    ),
                    List.of("architecture-review", "release-readiness"),
                    List.of("impact analysis", "repository refactor", "regression suite", "rollback note")
            ),
            new ScenarioDemonstration(
                    WorkflowScenario.AMBIGUOUS,
                    "Ambiguous: Branded Short Links",
                    "Support branded short links for business customers.",
                    List.of(
                            "Brand ownership verification is undefined",
                            "DNS/domain model is unspecified",
                            "Pricing and tenant boundaries are outside current scope"
                    ),
                    List.of(
                            "Stop for clarification on brand/domain ownership",
                            "Capture assumptions and excluded scope",
                            "Propose minimal MVP: tenant-owned domain metadata and policy gate",
                            "Defer DNS automation until ownership workflow is approved",
                            "Generate validation plan only after ambiguity is resolved"
                    ),
                    List.of(
                            "requirements ambiguity gate",
                            "human clarification checkpoint",
                            "decomposition after assumptions are approved",
                            "architecture approval gate",
                            "safe-stop if ownership policy remains undefined"
                    ),
                    List.of(
                            "Clarification checklist",
                            "Policy decision record",
                            "Threat model for domain takeover",
                            "Tests only after accepted scope"
                    ),
                    List.of("clarification-needed", "architecture-review", "release-readiness"),
                    List.of("clarification questions", "assumption log", "safe-stop criteria", "threat model outline")
            )
    );

    @Override
    public List<ScenarioDemonstration> all() {
        return scenarios;
    }

    @Override
    public Optional<ScenarioDemonstration> findByScenario(WorkflowScenario scenario) {
        return scenarios.stream()
                .filter(item -> item.scenario() == scenario)
                .findFirst();
    }
}
