package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PlanContractTest {
    @TempDir Path directory;
    @Test void emptyAndExistingRepositoriesProduceDifferentTasksAndValidateContracts() throws Exception {
        var mapper = new ObjectMapper();
        var planner = new RequirementPlanner(mapper);
        var empty = planner.analyze(RequirementSpec.aliases(), directory, "a");
        assertThat(empty.mode()).isEqualTo(EngineeringPlan.Mode.GREENFIELD);
        assertThat(empty.tasks()).anyMatch(t -> t.id().equals("bootstrap") && t.files().contains("pom.xml"));
        PlannerRepositoryFixture.create(directory);
        var existing = planner.analyze(RequirementSpec.aliases(), directory, "b");
        assertThat(existing.mode()).isEqualTo(EngineeringPlan.Mode.BROWNFIELD);
        assertThat(existing.tasks()).noneMatch(t -> t.id().equals("bootstrap"));
        assertThat(existing.tasks()).allMatch(t -> !t.requirementIds().isEmpty() && !t.entryGates().isEmpty());
        var task = existing.tasks().getFirst();
        var unknown = new EngineeringPlan.Task(task.id(), task.objective(), List.of("missing"), task.files(), task.criteria()).bind(existing.interpretation(), GovernancePolicy.defaultPolicy());
        assertThatThrownBy(() -> new PlanValidator().validate(List.of(unknown), existing.specification())).hasMessageContaining("Unknown dependency");
        var cycle = new EngineeringPlan.Task(task.id(), task.objective(), List.of(task.id()), task.files(), task.criteria()).bind(existing.interpretation(), GovernancePolicy.defaultPolicy());
        assertThatThrownBy(() -> new PlanValidator().validate(List.of(cycle), existing.specification())).hasMessageContaining("cycle");
        assertThatThrownBy(() -> new PlanValidator().validate(List.of(task), existing.specification())).hasMessageContaining("coverage");
        assertThatThrownBy(() -> new PlanValidator().validate(List.of(task, task), existing.specification())).isInstanceOf(IllegalArgumentException.class);
    }
}
