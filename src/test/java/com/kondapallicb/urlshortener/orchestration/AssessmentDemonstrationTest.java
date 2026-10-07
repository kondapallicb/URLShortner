package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.infrastructure.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class AssessmentDemonstrationTest {
    @Test void requirementToRepairedCodeToExecutedCriteriaToExactApprovalsToRestartedOutcome() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        Path directory = Path.of("target/assessment-proof/approved-outcome", java.util.UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(directory);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var database = WorkflowOwnershipStore.local(directory.resolve("db").toString());
        var repository = new DurableWorkflowRunRepository(database, mapper);
        var defective = new AliasImplementationAgent() {
            @Override public List<FileOperation> implement() {
                return super.implement().stream().map(op -> new FileOperation(op.path(),
                    op.content().replace("public CustomAliasController(", "public WrongConstructor("))).toList();
            }
        };
        var execution = new WorkspaceExecutionService(".", directory.resolve("evidence").toString(), "mvn", mapper, defective, database);
        var engine = new DefaultWorkflowEngine(repository, new DefaultScenarioCatalog(), java.time.Clock.systemUTC(), execution);
        var run = engine.start(WorkflowScenario.BROWNFIELD,
            "Add custom aliases with minimum length 8, maximum length 20, and expiry after one hour.");
        assertThat(run.pendingApprovals().getFirst().name()).isEqualTo("architecture-review");
        run = engine.approve(run.runId(), "engineering-lead", "Approve exact interpreted plan", execution.approvalHash(run));
        assertThat(run.pendingApprovals().getFirst().name()).isEqualTo("security-review");
        var evidence = execution.evidence(run.runId());
        assertThat(evidence.attempts()).hasSize(2);
        assertThat(evidence.attempts().getFirst().exitCode()).isNotZero();
        assertThat(evidence.attempts().getLast().exitCode()).isZero();
        run = engine.approve(run.runId(), "independent-security-reviewer", "Approve current policy and validation", execution.approvalHash(run));
        run = engine.approve(run.runId(), "engineering-lead", "Approve exact repaired outcome", execution.approvalHash(run));
        assertThat(run.state()).isEqualTo(ExecutionState.COMPLETED);
        var restartedRepository = new DurableWorkflowRunRepository(database, mapper);
        var restartedExecution = new WorkspaceExecutionService(".", directory.resolve("evidence").toString(), "mvn", mapper, new AliasImplementationAgent(), database);
        var summary = new com.kondapallicb.urlshortener.observability.EngineeringSummaryService(restartedRepository, restartedExecution).summary();
        assertThat(summary.releaseReadiness().status()).isEqualTo("VALIDATED_AND_APPROVED_FOR_REVIEW");
        assertThat(restartedRepository.findById(run.runId()).orElseThrow().decisions())
            .filteredOn(d -> d.approval() != null).hasSize(3);
        new GovernedFileService(mapper).persist(directory.resolve("demonstration.json"), java.util.Map.of(
            "run", run, "evidence", evidence, "summary", summary, "currentReleaseHash", restartedExecution.approvalHash(run)));
    }
}
