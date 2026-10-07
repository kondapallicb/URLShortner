package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RequirementPlannerTest {
    @TempDir Path directory;
    private final RequirementPlanner planner = new RequirementPlanner(new ObjectMapper());

    @Test void acceptanceCriteriaAndRepositoryAnalysisProduceDifferentTaskGraphs() throws Exception {
        var alias = new RequirementSpec(List.of(RequirementSpec.Capability.CUSTOM_ALIAS),
                new RequirementSpec.AliasOptions(5, 12, 120, RequirementSpec.AliasOptions.Alphabet.ALPHANUMERIC),
                RequirementSpec.aliases().acceptanceCriteria());
        var daily = new RequirementSpec(List.of(RequirementSpec.Capability.UTC_DAILY_ANALYTICS), null,
                List.of(RequirementSpec.Criterion.UTC_DAY_BOUNDARIES, RequirementSpec.Criterion.UNKNOWN_SLUG_REJECTED));
        Path repository = PlannerRepositoryFixture.create(directory);
        var aliasPlan = planner.analyze(alias, repository, "hash");
        var dailyPlan = planner.analyze(daily, repository, "hash");
        assertThat(aliasPlan.tasks()).isNotEqualTo(dailyPlan.tasks());
        assertThat(aliasPlan.tasks().getFirst().objective()).contains("minLength=5", "maxLength=12", "ttlSeconds=120");
        assertThat(aliasPlan.tasks().get(1).dependsOn()).containsExactly("custom_alias");
        assertThat(dailyPlan.acceptanceTests()).containsEntry(RequirementSpec.Criterion.UTC_DAY_BOUNDARIES,
                "com.kondapallicb.urlshortener.api.DailyAnalyticsAcceptanceTest#groupsByUtcDate");
        assertThat(aliasPlan.repositorySymbols()).anySatisfy((path, symbols) -> {
            assertThat(path).endsWith("UrlController.java");
            assertThat(symbols).contains("resolveAndRecordClick");
        });
        assertThat(new AliasImplementationAgent().implement(alias.alias()).getFirst().content())
                .contains("{5,12}", "plusSeconds(120)", "[A-Za-z0-9]").doesNotContain("{3,64}");
    }

    @Test void unsupportedTextAndUnknownSpecificationFieldsRequireClarification() {
        assertThatThrownBy(() -> planner.parse("Add custom aliases with case insensitive matching"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Clarification");
        assertThatThrownBy(() -> planner.parse("Add custom aliases with tenant ownership"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.parse("{\"capabilities\":[\"CUSTOM_ALIAS\"],\"caseInsensitive\":true}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.parse("{\"capabilities\":[\"UTC_DAILY_ANALYTICS\"],\"acceptanceCriteria\":[\"TENANT_OWNERSHIP\"]}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void criteriaWithoutImplementingCapabilitiesAreRejected() throws Exception {
        var spec = new RequirementSpec(List.of(RequirementSpec.Capability.UTC_DAILY_ANALYTICS), null,
                List.of(RequirementSpec.Criterion.ALIAS_REDIRECT));
        String json = new ObjectMapper().writeValueAsString(spec);
        assertThatThrownBy(() -> planner.parse(json)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no implementing capability");
    }

    @Test void commentsCannotImpersonateRepositoryIntegration() throws Exception {
        Path api = directory.resolve("src/main/java/example/UrlController.java");
        Files.createDirectories(api.getParent());
        Files.writeString(api, "package example; class UrlController { /* resolveAndRecordClick */ }");
        Files.writeString(api.resolveSibling("UrlMappingRepository.java"), "package example; interface UrlMappingRepository { void save(); }");
        assertThatThrownBy(() -> planner.analyze(RequirementSpec.aliases(), directory, "hash"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required integration");
    }
}
