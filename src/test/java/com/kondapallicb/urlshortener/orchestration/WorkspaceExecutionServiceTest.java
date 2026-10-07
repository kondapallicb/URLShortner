package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class WorkspaceExecutionServiceTest {
    @TempDir Path directory;

    @Test void generatesConnectedCodeExecutesTestsAndRepairsDiagnosedCompilerFailure() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        AliasImplementationAgent defective = new AliasImplementationAgent() {
            @Override public List<FileOperation> implement() {
                return super.implement().stream().map(op -> new FileOperation(op.path(),
                        op.content().replace("public CustomAliasController(", "public WrongConstructor("))).toList();
            }
        };
        var service = new WorkspaceExecutionService(Path.of(".").toAbsolutePath().toString(),
                Path.of("target/execution-review", java.util.UUID.randomUUID().toString()).toString(),
                "mvn", new ObjectMapper(), defective);
        var evidence = service.execute("repair-demo", "Add custom aliases");
        assertThat(evidence.attempts()).hasSize(2);
        assertThat(evidence.attempts().getFirst().exitCode()).isNotZero();
        assertThat(evidence.attempts().getLast().exitCode()).isZero();
        assertThat(evidence.readiness()).isEqualTo("VALIDATED_NOT_RELEASE_APPROVED");
        assertThat(evidence.rolledBack()).isFalse();
        assertThat(evidence.outcomeHash()).isNotEqualTo(evidence.baselineHash());
        assertThat(evidence.buildArtifactHash()).hasSize(64);
        assertThat(Files.isRegularFile(Path.of(evidence.buildArtifact()))).isTrue();
        assertThat(evidence.attempts().getLast().testReports()).anyMatch(p -> p.endsWith("CustomAliasAcceptanceTest.xml"));
        assertThat(Files.readString(Path.of(evidence.attempts().getLast().coverageReport()))).contains("CustomAliasController");
        assertThat(service.evidence("repair-demo")).isEqualTo(evidence);
        assertThat(Files.exists(Path.of("src/main/java/com/kondapallicb/urlshortener/api/CustomAliasController.java"))).isFalse();
    }

    @Test void unavailableMavenRecordsFailureAndRestoresBaseline() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var service = new WorkspaceExecutionService(".", directory.toString(), "/nonexistent/mvn", new ObjectMapper());
        var evidence = service.execute("rollback-demo", "Add custom aliases");
        assertThat(evidence.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(evidence.rolledBack()).isTrue();
        assertThat(evidence.attempts().getFirst().exitCode()).isEqualTo(-1);
        for (String path : evidence.changedFiles()) assertThat(Files.exists(Path.of(evidence.workspace()).resolve(path))).isFalse();
        assertThat(Files.readString(Path.of(evidence.attempts().getFirst().log()))).contains("Maven could not start");
    }

    @Test void realWorkflowRequiresBoundEvidenceAndInvalidatesApprovalsAfterClarification() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var execution = new WorkspaceExecutionService(".", directory.resolve("evidence").toString(), "mvn", mapper);
        var repository = new com.kondapallicb.urlshortener.infrastructure.DurableWorkflowRunRepository(
                directory.resolve("storage").toString(), mapper);
        var engine = new DefaultWorkflowEngine(repository, new DefaultScenarioCatalog(), java.time.Clock.systemUTC(), execution);
        var unclear = engine.start(WorkflowScenario.AMBIGUOUS, "Support branded links");
        assertThat(unclear.state()).isEqualTo(ExecutionState.SAFE_STOPPED);
        var planned = engine.clarify(unclear.runId(), "Add custom aliases");
        assertThat(engine.get(unclear.runId()).pendingApprovals()).isEmpty();
        String planHash = execution.approvalHash(planned);
        assertThatThrownBy(() -> engine.approve(planned.runId(), "reviewer", "approve", "wrong-hash"))
                .isInstanceOf(IllegalStateException.class);
        var validated = engine.approve(planned.runId(), "reviewer", "approve plan", planHash);
        assertThat(validated.state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
        String outcomeHash = execution.approvalHash(validated);
        assertThat(outcomeHash).isNotEqualTo(planHash);
        assertThatThrownBy(() -> engine.approve(validated.runId(), "reviewer", "approve outcome", planHash))
                .isInstanceOf(IllegalStateException.class);
        var completed = engine.approve(validated.runId(), "reviewer", "approve outcome", outcomeHash);
        assertThat(completed.state()).isEqualTo(ExecutionState.COMPLETED);
        var summary = new com.kondapallicb.urlshortener.observability.EngineeringSummaryService(repository, execution);
        assertThat(summary.summary().releaseReadiness().status()).isEqualTo("VALIDATED_AND_APPROVED_FOR_REVIEW");
        var restored = new com.kondapallicb.urlshortener.infrastructure.DurableWorkflowRunRepository(
                directory.resolve("storage").toString(), mapper);
        assertThat(restored.findById(completed.runId()).orElseThrow().state()).isEqualTo(ExecutionState.COMPLETED);
        Path generated = Path.of(execution.evidence(completed.runId()).workspace()).resolve(
                "src/main/java/com/kondapallicb/urlshortener/api/CustomAliasController.java");
        Files.writeString(generated, Files.readString(generated) + "\n// Changed after approval\n");
        assertThatThrownBy(() -> execution.approvalHash(completed)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
        assertThat(summary.summary().releaseReadiness().status()).isEqualTo("STALE_EVIDENCE");
        engine.clarify(completed.runId(), "Add custom aliases with revised acceptance criteria");
        assertThat(engine.get(completed.runId()).state()).isEqualTo(ExecutionState.SAFE_STOPPED);
    }
}
