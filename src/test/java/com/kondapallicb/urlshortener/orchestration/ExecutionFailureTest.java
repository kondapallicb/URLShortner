package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ExecutionFailureTest {
    private final Path directory = Path.of("target/assessment-proof/ExecutionFailureTest", java.util.UUID.randomUUID().toString()).toAbsolutePath();
    @org.junit.jupiter.api.BeforeEach void initializeEvidenceDirectory() throws Exception { Files.createDirectories(directory); }
    @ParameterizedTest @ValueSource(strings = {"startup", "parsing", "finalization"})
    void failuresProduceClassifiedHonestOutcomesAndVerifiedRollback(String phase) throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper()) {
            @Override protected Process startBuild(ProcessBuilder builder) throws java.io.IOException {
                if (phase.equals("startup")) throw new java.io.IOException("Injected build startup failure");
                return super.startBuild(builder);
            }
            @Override protected void verifyAcceptance(EngineeringPlan plan, ExecutionEvidence.ValidationAttempt attempt) throws java.io.IOException {
                if (phase.equals("parsing")) throw new java.io.IOException("Injected report parsing failure");
                super.verifyAcceptance(plan, attempt);
            }
            @Override protected void beforeFinalization(Path audit) throws java.io.IOException {
                if (phase.equals("finalization")) throw new java.io.IOException("Injected finalization failure");
            }
        };
        var result = service.execute("failure-" + phase, "Add custom aliases");
        assertThat(result.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(result.rolledBack()).isTrue();
        assertThat(result.buildArtifact()).isNull();
        assertThat(Files.readString(directory.resolve("failure-" + phase + "/terminal.json")))
            .contains(phase.equals("startup") ? "TOOL_UNAVAILABLE" : "EVIDENCE_INVALID", "VERIFIED_ROLLBACK");
        assertThat(Files.exists(Path.of(result.workspace()).resolve("src/main/java/com/kondapallicb/urlshortener/api/CustomAliasController.java"))).isFalse();
        assertThat(result.attempts()).hasSize(1);
        assertThat(Files.exists(Path.of(result.attempts().getFirst().log()))).isTrue();
    }
    @Test void failedEvidenceStorageNeverFinalizesReadinessAndRetainsRecoveryJournal() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper()) {
            @Override protected void writeJson(Path path, Object value) throws java.io.IOException {
                if (path.getFileName().toString().equals("evidence.json")) throw new java.io.IOException("Injected evidence persistence failure");
                super.writeJson(path, value);
            }
        };
        assertThatThrownBy(() -> service.execute("evidence-failure", "Add custom aliases"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("recoverable application journal");
        assertThatThrownBy(() -> service.evidence("evidence-failure")).hasMessageContaining("No finalized");
        assertThat(Files.exists(directory.resolve("evidence-failure/application/journal.json"))).isTrue();
        assertThat(Files.readString(directory.resolve("evidence-failure/coordinator.json"))).contains("FINALIZE", "\"evidencePersisted\" : false");
    }
    @Test void failedRollbackIsRecordedAndCanNeverBeApproved() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var service = new WorkspaceExecutionService(".", directory.toString(), "/nonexistent/mvn", new ObjectMapper()) {
            @Override protected void restoreBaseline(Path baseline, Path workspace, String expectedHash) throws java.io.IOException {
                throw new java.io.IOException("Injected rollback failure");
            }
        };
        var evidence = service.execute("rollback-failure", "Add custom aliases");
        assertThat(evidence.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(evidence.rolledBack()).isFalse();
        assertThat(Files.readString(directory.resolve("rollback-failure/terminal.json")))
            .contains("ROLLBACK_FAILED", "SAFE_STOP_MANUAL_RECOVERY");
    }
    @Test void unsupportedClauseStopsBeforeAnySourceMutation() throws Exception {
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper());
        var evidence = service.execute("unsupported", "Add custom aliases and support an undefined proprietary ownership protocol.");
        assertThat(evidence.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(evidence.changedFiles()).isEmpty();
        assertThat(Files.exists(directory.resolve("unsupported/workspace"))).isFalse();
        assertThat(Files.readString(directory.resolve("unsupported/terminal.json"))).contains("REQUIREMENT_UNSUPPORTED");
    }
    @ParameterizedTest @ValueSource(strings = {"deleted", "renamed", "skipped", "excluded"})
    void requiredGeneratedTestsMustReallyExecute(String change) throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper()) {
            @Override protected List<FileOperation> operations(EngineeringPlan plan, AliasImplementationAgent implementation) {
                return super.operations(plan, implementation).stream()
                    .filter(op -> !change.equals("excluded") || !op.path().endsWith("CustomAliasAcceptanceTest.java"))
                    .map(op -> {
                        if (!op.path().endsWith("CustomAliasAcceptanceTest.java")) return op;
                        String content = op.content();
                        content = switch (change) {
                            case "deleted" -> content.replace("@Test void aliasBoundariesAndTtl()", "void aliasBoundariesAndTtl()");
                            case "renamed" -> content.replace("aliasBoundariesAndTtl()", "unapprovedBehavior()");
                            case "skipped" -> content.replace("@Test void aliasBoundariesAndTtl()", "@org.junit.jupiter.api.Disabled @Test void aliasBoundariesAndTtl()");
                            default -> content;
                        };
                        return new FileOperation(op.path(), content);
                    }).toList();
            }
        };
        var result = service.execute("missing-test-" + change, "Add custom aliases");
        assertThat(result.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(result.rolledBack()).isTrue();
        assertThat(result.attempts()).hasSize(1);
        assertThat(Files.readString(Path.of(result.attempts().getFirst().log()).getParent().resolve("validation.json")))
            .contains("\"executed\" : false");
    }
    @Test void jacocoThresholdReallyFailsMavenVerification() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var defective = new AliasImplementationAgent() {
            @Override public List<FileOperation> implement() {
                return super.implement().stream().map(op -> {
                    int closing = op.content().lastIndexOf('}');
                    String dead = "\npublic int uncovered() {\nint value = 0;\n" + "value++;\n".repeat(80) + "return value;\n}\n";
                    return new FileOperation(op.path(), op.content().substring(0, closing) + dead + "}\n");
                }).toList();
            }
        };
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper(), defective);
        var evidence = service.execute("coverage-failure", "Add custom aliases");
        assertThat(evidence.attempts()).hasSize(1);
        assertThat(evidence.attempts().getFirst().exitCode()).isNotZero();
        assertThat(evidence.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(Files.readString(Path.of(evidence.attempts().getFirst().log()))).contains("Coverage checks have not been met");
        assertThat(Files.readString(directory.resolve("coverage-failure/terminal.json"))).contains("EVIDENCE_INVALID");
    }
}
