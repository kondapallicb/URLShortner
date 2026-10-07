package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyEvaluatorTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    @Test void securityChecksRejectCredentialsUnplannedFilesAndDisabledReview() throws Exception {
        var plan = new RequirementPlanner(new ObjectMapper()).analyze(RequirementSpec.aliases(), PlannerRepositoryFixture.create(directory), "hash");
        var operations = new ArrayList<>(new AliasImplementationAgent().implement());
        operations.addAll(new AliasTestingAgent().tests(RequirementSpec.AliasOptions.defaults()));
        var evaluator = new PolicyEvaluator();
        assertThat(evaluator.evaluate(plan, GovernancePolicy.defaultPolicy(), operations).passed()).isTrue();
        var original = operations.getFirst();
        operations.set(0, new FileOperation(original.path(), original.content() + "\nString password = \"credential\";"));
        assertThat(evaluator.evaluate(plan, GovernancePolicy.defaultPolicy(), operations).passed()).isFalse();
        operations.set(0, original);
        operations.add(new FileOperation("src/main/java/Unplanned.java", "class Unplanned {}"));
        assertThat(evaluator.evaluate(plan, GovernancePolicy.defaultPolicy(), operations).passed()).isFalse();
        assertThat(evaluator.evaluate(plan, new GovernancePolicy(1, false, true, List.of()), List.of(original)).passed()).isFalse();
    }
}
