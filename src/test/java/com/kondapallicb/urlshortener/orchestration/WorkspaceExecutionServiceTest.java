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
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;

    @Test void parameterizedAliasAndAnalyticsCriteriaGenerateAndValidateConnectedChanges() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var spec = new RequirementSpec(List.of(RequirementSpec.Capability.CUSTOM_ALIAS, RequirementSpec.Capability.UTC_DAILY_ANALYTICS),
                new RequirementSpec.AliasOptions(5, 12, 120, RequirementSpec.AliasOptions.Alphabet.ALPHANUMERIC),
                java.util.stream.Stream.concat(RequirementSpec.aliases().acceptanceCriteria().stream(),
                        java.util.stream.Stream.of(RequirementSpec.Criterion.UTC_DAY_BOUNDARIES, RequirementSpec.Criterion.UNKNOWN_SLUG_REJECTED)).toList());
        var mapper = new ObjectMapper();
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", mapper);
        var evidence = service.execute("combined", mapper.writeValueAsString(spec));
        assertThat(evidence.readiness()).as(evidence.failureReason()).isEqualTo("VALIDATED_NOT_RELEASE_APPROVED");
        assertThat(evidence.changedFiles()).hasSize(5);
        assertThat(service.policyReport("combined").passed()).isTrue();
        assertThat(evidence.isolation()).isEqualTo("MACOS_SEATBELT_OFFLINE");
        assertThat(Files.readString(Path.of(evidence.workspace()).resolve(evidence.changedFiles().getFirst())))
                .contains("{5,12}", "plusSeconds(120)");
        assertThat(evidence.attempts().getFirst().testReports()).anyMatch(p -> p.endsWith("DailyAnalyticsAcceptanceTest.xml"));
    }

    @Test void interpretsNaturalLanguageAndExecutesControlledExpiryProof() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        Path proof = Path.of("target/assessment-proof/hour-expiry", java.util.UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(proof);
        var service = new WorkspaceExecutionService(".", proof.toString(), "mvn", new ObjectMapper());
        var evidence = service.execute("hour-expiry", "Add custom aliases with minimum length 8, maximum length 20, and expiry after one hour.");
        assertThat(evidence.readiness()).as(evidence.failureReason()).isEqualTo("VALIDATED_NOT_RELEASE_APPROVED");
        assertThat(Files.readString(proof.resolve("hour-expiry/attempt-1/validation.json")))
            .contains("aliasBoundariesAndTtl", "lineCovered", "traceability");
        assertThat(Files.readString(Path.of(evidence.workspace()).resolve("src/test/java/com/kondapallicb/urlshortener/api/CustomAliasAcceptanceTest.java")))
            .contains("status().isGone()", "plusSeconds(3600)");
    }

    @Test void greenfieldGeneratesAndValidatesApplicationFromEmptySource() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        Path proof = Path.of("target/assessment-proof/greenfield", java.util.UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(proof);
        Path empty = proof.resolve("empty");
        Files.createDirectories(empty);
        var service = new WorkspaceExecutionService(empty.toString(), proof.resolve("proof").toString(), "mvn", new ObjectMapper());
        assertThat(service.detailedPlan("Add custom aliases").mode()).isEqualTo(EngineeringPlan.Mode.GREENFIELD);
        var evidence = service.execute("new-app", "Add custom aliases");
        assertThat(evidence.readiness()).as(evidence.failureReason()).isEqualTo("VALIDATED_NOT_RELEASE_APPROVED");
        assertThat(Files.exists(empty.resolve("src"))).isFalse();
        assertThat(Files.readString(Path.of(evidence.workspace()).resolve("pom.xml"))).contains("coverage-check");
    }

    @Test void failedAcceptanceTestsTriggerProductionRepairWithoutWeakeningTests() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var defective = new AliasImplementationAgent() {
            @Override public List<FileOperation> implement() {
                return super.implement().stream().map(op -> new FileOperation(op.path(),
                        op.content().replace("HttpStatus.CREATED", "HttpStatus.OK"))).toList();
            }
        };
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper(), defective);
        var evidence = service.execute("acceptance-repair", "Add custom aliases");
        assertThat(evidence.attempts()).hasSize(2);
        assertThat(evidence.attempts().getFirst().exitCode()).isNotZero();
        assertThat(evidence.attempts().getLast().exitCode()).isZero();
        assertThat(Files.readString(directory.resolve("acceptance-repair/attempt-1/repair.json")))
                .contains("src/main/java/").doesNotContain("src/test/java/");
    }

    @Test void partialFileWriteFailureRestoresAllSourceAndPersistsFailure() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper(), new AliasImplementationAgent()) {
            @Override protected void applyOperation(Path workspace, FileOperation operation) throws java.io.IOException {
                if (count.incrementAndGet() == 2) throw new java.io.IOException("Injected second-file write failure");
                super.applyOperation(workspace, operation);
                Path existing = workspace.resolve("src/main/java/com/kondapallicb/urlshortener/api/UrlController.java");
                Files.writeString(existing, Files.readString(existing) + "\n// Simulated worker mutation\n");
            }
        };
        var evidence = service.execute("io-failure", "Add custom aliases");
        assertThat(evidence.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(evidence.rolledBack()).isTrue();
        assertThat(evidence.failureReason()).contains("second-file write failure");
        assertThat(evidence.attempts()).isEmpty();
        for (String path : evidence.changedFiles()) assertThat(Files.exists(Path.of(evidence.workspace()).resolve(path))).isFalse();
        assertThat(service.evidence("io-failure")).isEqualTo(evidence);
        assertThat(Files.readString(Path.of(evidence.workspace()).resolve("src/main/java/com/kondapallicb/urlshortener/api/UrlController.java")))
                .isEqualTo(Files.readString(Path.of("src/main/java/com/kondapallicb/urlshortener/api/UrlController.java")));
    }

    @Test void retryBudgetZeroDoesNotRepairAndRestoresSnapshot() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var defective = new AliasImplementationAgent() {
            @Override public List<FileOperation> implement() {
                return super.implement().stream().map(op -> new FileOperation(op.path(),
                        op.content().replace("public CustomAliasController(", "public WrongConstructor("))).toList();
            }
        };
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper(), defective);
        var policy = new GovernancePolicy(0, true, true, GovernancePolicy.defaultPolicy().guardrails());
        var evidence = service.execute("no-retry", "Add custom aliases", policy);
        assertThat(evidence.attempts()).hasSize(1);
        assertThat(evidence.rolledBack()).isTrue();
        assertThat(evidence.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(Files.exists(directory.resolve("no-retry/attempt-1/repair.json"))).isFalse();
    }

    @Test void timeoutKillsWorkerAndRestoresSnapshot() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper(), new AliasImplementationAgent()) {
            @Override protected java.time.Duration validationTimeout() { return java.time.Duration.ofMillis(50); }
        };
        var evidence = service.execute("timeout", "Add custom aliases");
        assertThat(evidence.attempts().getFirst().timedOut()).isTrue();
        assertThat(evidence.rolledBack()).isTrue();
        assertThat(evidence.readiness()).isEqualTo("SAFE_STOPPED");
    }

    @Test void policyViolationPreventsExecutionAndRollsBack() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var defective = new AliasImplementationAgent() {
            @Override public List<FileOperation> implement() {
                return super.implement().stream().map(op -> new FileOperation(op.path(),
                        op.content() + "\nString password = \"embedded-credential\";\n")).toList();
            }
        };
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper(), defective);
        var evidence = service.execute("policy-failure", "Add custom aliases");
        assertThat(evidence.attempts()).isEmpty();
        assertThat(evidence.readiness()).isEqualTo("SAFE_STOPPED");
        assertThat(service.policyReport("policy-failure").passed()).isFalse();
        assertThat(Files.exists(directory.resolve("policy-failure/operations.json"))).isFalse();
        assertThat(evidence.rolledBack()).isTrue();
    }

    @Test void interruptionStopsWorkerPersistsCancellationAndRestoresSnapshot() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        var service = new WorkspaceExecutionService(".", directory.toString(), "mvn", new ObjectMapper());
        var result = new java.util.concurrent.atomic.AtomicReference<ExecutionEvidence>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var flag = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = new Thread(() -> {
            try { result.set(service.execute("cancelled", "Add custom aliases")); flag.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        try {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (!Files.exists(directory.resolve("cancelled/attempt-1/maven.log")) && worker.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(worker.isAlive()).isTrue();
            worker.interrupt();
            worker.join(10000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(result.get().attempts().getFirst().cancelled()).isTrue();
            assertThat(result.get().rolledBack()).isTrue();
            assertThat(result.get().readiness()).isEqualTo("SAFE_STOPPED");
            assertThat(flag.get()).isTrue();
            assertThat(service.evidence("cancelled")).isEqualTo(result.get());
        } finally { if (worker.isAlive()) { worker.interrupt(); worker.join(10000); } }
    }

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
        assertThat(validated.pendingApprovals().getFirst().name()).isEqualTo("security-review");
        String securityHash = execution.approvalHash(validated);
        final var securityRun = validated;
        assertThatThrownBy(() -> engine.approve(securityRun.runId(), "reviewer", "self review", securityHash))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("independent");
        validated = engine.approve(validated.runId(), "security-reviewer", "approve security", securityHash);
        final var releaseRun = validated;
        String outcomeHash = execution.approvalHash(validated);
        assertThat(outcomeHash).isNotEqualTo(planHash);
        assertThatThrownBy(() -> engine.approve(releaseRun.runId(), "reviewer", "approve outcome", planHash))
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
