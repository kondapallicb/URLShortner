package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.infrastructure.WorkflowOwnershipStore;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CrashRecoveryTest {
    private final Path directory = Path.of("target/assessment-proof/CrashRecoveryTest", java.util.UUID.randomUUID().toString()).toAbsolutePath();
    @org.junit.jupiter.api.BeforeEach void initializeEvidenceDirectory() throws Exception { Files.createDirectories(directory); }
    @Test void startupReconcilesPersistedRunningWorkflowAndRequiresNewSecurityApproval() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        String jdbc = System.getProperty("workflow.test.jdbc", directory.resolve("db").toString());
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classpath,
            CrashRecoveryWorker.class.getName(), directory.resolve("evidence").toString(), "unused", jdbc, "workflow")
            .redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            Path marker = directory.resolve("evidence/worker-ready");
            while (!Files.exists(marker) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(Files.exists(marker)).as(Files.readString(directory.resolve("worker.log"))).isTrue();
            String id = Files.readString(directory.resolve("evidence/workflow-id"));
            child.destroyForcibly(); assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue(); Thread.sleep(500);
            javax.sql.DataSource database = jdbc.startsWith("jdbc:postgresql:")
                ? new org.springframework.jdbc.datasource.DriverManagerDataSource(jdbc, "workflow_test", "")
                : WorkflowOwnershipStore.local(jdbc);
            var mapper = new ObjectMapper().findAndRegisterModules();
            var repository = new com.kondapallicb.urlshortener.infrastructure.DurableWorkflowRunRepository(database, mapper);
            assertThat(repository.findById(id).orElseThrow().state()).isEqualTo(ExecutionState.RUNNING);
            var execution = new WorkspaceExecutionService(".", directory.resolve("evidence").toString(), "mvn", mapper, new AliasImplementationAgent(), database);
            var engine = new DefaultWorkflowEngine(repository, new DefaultScenarioCatalog(), java.time.Clock.systemUTC(), execution);
            engine.reconcileInterruptedRuns();
            var reconciled = engine.get(id);
            assertThat(reconciled.state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
            assertThat(reconciled.pendingApprovals().getFirst().name()).isEqualTo("security-review");
            assertThat(execution.evidence(id).workspace()).contains("recovery-2/workspace");
            assertThat(new com.kondapallicb.urlshortener.observability.EngineeringSummaryService(repository, execution).summary()
                .releaseReadiness().status()).isNotEqualTo("VALIDATED_AND_APPROVED_FOR_REVIEW");
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
    }

    @Test void killedWorkerIsReconciledInIsolatedWorkspaceAndCannotCreateFalseReadiness() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        String jdbc = System.getProperty("workflow.test.jdbc", directory.resolve("db").toString());
        String id = java.util.UUID.randomUUID().toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classpath,
            CrashRecoveryWorker.class.getName(), directory.resolve("evidence").toString(), id, jdbc)
            .redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            Path marker = directory.resolve("evidence/worker-ready");
            while (!Files.exists(marker) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(Files.exists(marker)).as(Files.readString(directory.resolve("worker.log"))).isTrue();
            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);
            javax.sql.DataSource database = jdbc.startsWith("jdbc:postgresql:")
                ? new org.springframework.jdbc.datasource.DriverManagerDataSource(jdbc, "workflow_test", "")
                : WorkflowOwnershipStore.local(jdbc);
            var store = new WorkflowOwnershipStore(database, new ObjectMapper().findAndRegisterModules());
            assertThat(store.completed(id)).isEmpty();
            var replacement = new WorkspaceExecutionService(".", directory.resolve("evidence").toString(), "mvn",
                new ObjectMapper(), new AliasImplementationAgent(), database);
            var recovered = replacement.execute(id, "Add custom aliases");
            assertThat(recovered.readiness()).as(recovered.failureReason()).isEqualTo("VALIDATED_NOT_RELEASE_APPROVED");
            assertThat(recovered.workspace()).contains("recovery-2/workspace");
            assertThat(Files.readString(Path.of(recovered.workspace()).getParent().resolve("reconciliation.json")))
                .contains("baselineVerified", "journalsInspected", "APPLYING", "incompleteEvidenceInvalidated");
            assertThat(new WorkspaceExecutionService(".", directory.resolve("evidence").toString(), "mvn",
                new ObjectMapper(), new AliasImplementationAgent(), database).evidence(id)).isEqualTo(recovered);
            Path oldFile = directory.resolve("evidence").resolve(id).resolve("workspace/src/main/java/com/kondapallicb/urlshortener/api/CustomAliasController.java");
            Files.writeString(oldFile, "stale worker wrote here");
            assertThat(Files.readString(Path.of(recovered.workspace()).resolve("src/main/java/com/kondapallicb/urlshortener/api/CustomAliasController.java")))
                .doesNotContain("stale worker");
            assertThat(store.attempts(id)).hasSize(2);
            assertThat(recovered.readiness()).doesNotContain("APPROVED_FOR_REVIEW");
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
    }
}
