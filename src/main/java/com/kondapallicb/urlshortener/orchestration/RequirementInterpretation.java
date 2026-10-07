package com.kondapallicb.urlshortener.orchestration;

import java.util.List;
import java.util.Map;

public record RequirementInterpretation(String requirementId, String originalText, RequirementSpec normalized,
        Map<String, Parameter> parameters, List<AcceptanceCriterion> criteria, List<String> assumptions,
        List<String> unresolvedQuestions, List<String> unsupportedClauses, Status status) {
    public enum Status { READY, NEEDS_CLARIFICATION, UNSUPPORTED }
    public record Parameter(Object value, String sourceText) { }
    public record AcceptanceCriterion(String id, RequirementSpec.Criterion behavior) { }
}
