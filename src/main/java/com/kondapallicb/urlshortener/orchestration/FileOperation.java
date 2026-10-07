package com.kondapallicb.urlshortener.orchestration;

import java.util.List;
import java.util.Map;

public record FileOperation(String operationId, Type type, String path, String completeContent,
        String expectedBeforeHash, boolean expectedAbsence, String reason, List<String> requirementIds,
        List<String> criterionIds, String taskId, String revisionId, Map<String, String> inputArtifactHashes) {
    public enum Type { CREATE, UPDATE, DELETE }
    public FileOperation(String path, String content) {
        this(null, Type.CREATE, path, content, null, true, "Generated artifact", List.of(), List.of(), null, null, Map.of());
    }
    public String content() { return completeContent; }
    public FileOperation bind(EngineeringPlan plan, String revision, String beforeHash) {
        var task = plan.tasks().stream().filter(t -> t.writeScope().contains(path)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No planned task owns " + path));
        return new FileOperation(operationId == null ? task.id() + ":" + RequirementInterpreter.hash(path).substring(0, 12) : operationId,
            beforeHash == null || type == Type.DELETE ? type : Type.UPDATE, path, completeContent, beforeHash, beforeHash == null,
            reason, task.requirementIds(), task.criterionIds(), task.id(), revision,
            inputs(plan));
    }
    public static Map<String, String> inputs(EngineeringPlan plan) {
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            return Map.of("baseline", plan.baselineHash(), "requirement", RequirementInterpreter.hash(plan.interpretation().originalText()),
                "plan", RequirementInterpreter.hash(json.writeValueAsString(plan)), "policy", RequirementInterpreter.hash(json.writeValueAsString(plan.applicablePolicy())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
