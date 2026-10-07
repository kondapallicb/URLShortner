package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequirementInterpreterTest {
    private final RequirementInterpreter interpreter = new RequirementInterpreter(new ObjectMapper());
    @Test void interpretsAllConstraintsAndRecordsDefaultsAndSources() {
        var result = interpreter.interpret("Add custom aliases with minimum length 8, maximum length 20, and expiry after one hour.", true);
        assertThat(result.status()).isEqualTo(RequirementInterpretation.Status.READY);
        assertThat(result.normalized().alias().minLength()).isEqualTo(8);
        assertThat(result.normalized().alias().maxLength()).isEqualTo(20);
        assertThat(result.normalized().alias().ttlSeconds()).isEqualTo(3600);
        assertThat(result.parameters().get("ttlSeconds").sourceText()).isEqualTo("expiry after one hour");
        assertThat(result.assumptions()).isNotEmpty();
        assertThat(result.criteria()).hasSize(5);
        assertThat(new AliasTestingAgent().tests(result.normalized().alias()).getFirst().content())
            .contains("aaaaaaa", "aaaaaaaaaaaaaaaaaaaaa", "plusSeconds(3600)", "status().isGone()");
    }
    @Test void reservedAliasesAndMandatoryBehaviorCannotBeOmitted() throws Exception {
        var result = interpreter.interpret("Add custom aliases with reserved aliases [api, branded] and case sensitive.", true);
        assertThat(result.status()).isEqualTo(RequirementInterpretation.Status.READY);
        assertThat(result.normalized().alias().reservedAliases()).containsExactly("api", "branded");
        assertThat(new AliasTestingAgent().tests(result.normalized().alias()).getFirst().content()).contains("branded");
        var partial = new RequirementSpec(java.util.List.of(RequirementSpec.Capability.CUSTOM_ALIAS),
            RequirementSpec.AliasOptions.defaults(), java.util.List.of(RequirementSpec.Criterion.ALIAS_REDIRECT));
        assertThat(interpreter.interpret(new ObjectMapper().writeValueAsString(partial), true).criteria()).hasSize(5);
    }
    @Test void neverDropsUnknownClausesAndRequiresExplicitValuesWhenDefaultsForbidden() {
        var result = interpreter.interpret("Add custom aliases and support an undefined proprietary ownership protocol.", true);
        assertThat(result.status()).isEqualTo(RequirementInterpretation.Status.UNSUPPORTED);
        assertThat(result.unsupportedClauses()).anyMatch(v -> v.contains("ownership protocol"));
        assertThat(result.unresolvedQuestions()).isNotEmpty();
        assertThat(interpreter.interpret("Add custom aliases", false).status()).isEqualTo(RequirementInterpretation.Status.NEEDS_CLARIFICATION);
    }
}
