package com.kondapallicb.urlshortener.orchestration;

import java.util.List;
import java.util.Map;

public record EngineeringPlan(RequirementSpec specification, String baselineHash,
        Map<String, List<String>> repositorySymbols, List<Task> tasks,
        Map<RequirementSpec.Criterion, String> acceptanceTests, List<String> generatedClasses,
        Mode mode, RepositoryAnalysis repositoryAnalysis, RequirementInterpretation interpretation,
        List<String> availableCapabilities, GovernancePolicy applicablePolicy) {
    public enum Mode { GREENFIELD, BROWNFIELD }
    public record RepositoryAnalysis(List<String> files, List<String> runtimeEntrypoints,
        List<String> serviceBoundaries, List<String> repositoryBoundaries, List<String> buildFiles,
        List<String> tests, List<String> existingBehaviors) { }
    public record Task(String id, String objective, List<String> dependsOn, List<String> files,
            List<RequirementSpec.Criterion> criteria, String agentRole, List<String> requirementIds,
            List<String> criterionIds, List<String> inputArtifacts, List<String> expectedOutputs,
            List<String> readScope, List<String> writeScope, List<String> entryGates,
            List<String> exitGates, int maxAttempts, String fallback) {
        public Task(String id, String objective, List<String> dependsOn, List<String> files,
                List<RequirementSpec.Criterion> criteria) {
            this(id, objective, dependsOn, files, criteria, id.endsWith("-acceptance") ? "TESTING" : id.equals("bootstrap") ? "BOOTSTRAP" : id.equals("documentation") ? "DOCUMENTATION" : "IMPLEMENTATION",
                List.of(), List.of(), List.of("approved-baseline"), files, List.of("src/", "pom.xml"),
                files, List.of("CURRENT_INPUTS", "APPROVED_PLAN"), List.of("VALIDATED_ARTIFACTS"), 3, "VERIFIED_ROLLBACK");
        }
        public Task bind(RequirementInterpretation interpreted, GovernancePolicy policy) {
            return new Task(id, objective, dependsOn, files, criteria, agentRole, List.of(interpreted.requirementId()),
                criteria.stream().map(c -> interpreted.requirementId() + ":" + c).toList(), inputArtifacts, expectedOutputs,
                readScope, writeScope, entryGates, exitGates, policy.maxRetries() + 1, fallback);
        }
    }
}
