package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;

public class ExecutionCoordinator {
    private final Path source;
    private final Path evidenceRoot;
    private final String maven;
    private final ObjectMapper mapper;
    private final AliasImplementationAgent implementationAgent;
    private final RequirementPlanner planner;
    private final PolicyEvaluator policies = new PolicyEvaluator();
    private final SandboxRunner sandbox = new SandboxRunner();
    private final ExecutionJournal coordinator;
    private final GovernedFileService fileService;
    private final com.kondapallicb.urlshortener.infrastructure.WorkflowOwnershipStore ownership;
    private final String workerId = java.util.UUID.randomUUID().toString();
    private final ThreadLocal<com.kondapallicb.urlshortener.infrastructure.WorkflowOwnershipStore.Claim> currentClaim = new ThreadLocal<>();

    public ExecutionCoordinator(@Value("${app.execution.repository:.}") String source,
            @Value("${app.execution.evidence:./workflow-evidence}") String evidenceRoot,
            @Value("${app.execution.maven:mvn}") String maven, ObjectMapper mapper, javax.sql.DataSource database) {
        this(source, evidenceRoot, maven, mapper, new AliasImplementationAgent(), database);
    }
    public ExecutionCoordinator(String source, String evidenceRoot, String maven, ObjectMapper mapper) {
        this(source, evidenceRoot, maven, mapper, new AliasImplementationAgent());
    }
    ExecutionCoordinator(String source, String evidenceRoot, String maven, ObjectMapper mapper, AliasImplementationAgent implementationAgent) {
        this(source, evidenceRoot, maven, mapper, implementationAgent,
            com.kondapallicb.urlshortener.infrastructure.WorkflowOwnershipStore.local(evidenceRoot + "/.ownership"));
    }
    ExecutionCoordinator(String source, String evidenceRoot, String maven, ObjectMapper mapper,
            AliasImplementationAgent implementationAgent, javax.sql.DataSource database) {
        this.source = Path.of(source).toAbsolutePath().normalize();
        this.evidenceRoot = Path.of(evidenceRoot).toAbsolutePath().normalize();
        this.maven = maven;
        this.mapper = mapper.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.implementationAgent = implementationAgent;
        this.planner = new RequirementPlanner(this.mapper);
        this.fileService = new GovernedFileService(mapper);
        this.coordinator = new ExecutionJournal(this.mapper);
        this.ownership = new com.kondapallicb.urlshortener.infrastructure.WorkflowOwnershipStore(database, this.mapper);
    }

    public RequirementInterpretation interpretation(String requirement) {
        return new RequirementInterpreter(mapper).interpret(requirement, true);
    }
    public boolean isReady(String requirement) {
        try { planner.parse(requirement); return true; }
        catch (IllegalArgumentException unsupported) { return false; }
    }
    public EngineeringPlan detailedPlan(String requirement) { return detailedPlan(requirement, GovernancePolicy.defaultPolicy()); }
    public EngineeringPlan detailedPlan(String requirement, GovernancePolicy policy) {
        try { return planner.analyze(planner.parse(requirement, policy.permitRecordedDefaults()), source, hash(source), policy,
            new RequirementInterpreter(mapper).interpret(requirement, policy.permitRecordedDefaults())); }
        catch (IOException failure) { throw new IllegalStateException("Repository analysis failed", failure); }
    }
    public List<String> plan(String requirement) {
        if (!isReady(requirement)) {
            try { planner.parse(requirement); }
            catch (IllegalArgumentException unclear) { return List.of("Clarification required: " + unclear.getMessage()); }
        }
        EngineeringPlan plan = detailedPlan(requirement);
        var outputs = new ArrayList<String>();
        outputs.add("Baseline SHA-256: " + plan.baselineHash());
        for (var task : plan.tasks()) outputs.add(task.id() + ": " + task.objective() + "; dependencies=" + task.dependsOn()
                + "; files=" + task.files() + "; criteria=" + task.criteria());
        outputs.add("Acceptance traceability: " + plan.acceptanceTests());
        return List.copyOf(outputs);
    }
    public WorkflowGraph graph(String requirement, GovernancePolicy policy) {
        return WorkflowGraph.fromPlan(detailedPlan(requirement, policy), policy);
    }
    protected List<FileOperation> operations(EngineeringPlan plan, AliasImplementationAgent aliasAgent) {
        var operations = new ArrayList<FileOperation>();
        for (var capability : plan.specification().capabilities()) {
            if (capability == RequirementSpec.Capability.CUSTOM_ALIAS) {
                operations.addAll(aliasAgent.implement(plan.specification().alias()));
                operations.addAll(new AliasTestingAgent().tests(plan.specification().alias()));
            } else {
                operations.addAll(new DailyAnalyticsAgent().implement());
                operations.addAll(new DailyAnalyticsAgent().tests());
            }
        }
        if (plan.mode() == EngineeringPlan.Mode.GREENFIELD) operations.addAll(new GreenfieldAgent().scaffold());
        operations.add(new FileOperation("GENERATED_README.md", "# Generated Requirement\n\n" + plan.interpretation().originalText() + "\n\nCriteria: " + plan.interpretation().criteria() + "\n\nRun: mvn clean verify\n"));
        var order = plan.tasks().stream().flatMap(task -> task.files().stream()).toList();
        operations.sort(java.util.Comparator.comparingInt(op -> order.indexOf(op.path())));
        return List.copyOf(operations);
    }

    public ExecutionEvidence execute(String runId, String requirement) {
        return execute(runId, requirement, GovernancePolicy.defaultPolicy());
    }
    public ExecutionEvidence execute(String runId, String requirement, GovernancePolicy policy) {
        validId(runId);
        var completed = ownership.completed(runId);
        if (completed.isPresent()) {
            var prior = completed.orElseThrow();
            if (!java.util.Objects.equals(prior.requirement(), requirement)) throw new IllegalStateException("Requirement revision differs");
            return prior;
        }
        var claim = ownership.claim(runId, workerId, requirement, ownershipLease())
            .orElseThrow(() -> new IllegalStateException("Another worker owns this attempt"));
        currentClaim.set(claim);
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        var lost = new java.util.concurrent.atomic.AtomicBoolean();
        long interval = Math.max(10, ownershipLease().toMillis() / 3);
        scheduler.scheduleAtFixedRate(() -> {
            try { ownership.heartbeat(claim, ownershipLease()); }
            catch (RuntimeException expired) { lost.set(true); }
        }, interval, interval, TimeUnit.MILLISECONDS);
        try {
            Path root = evidenceRoot.resolve(runId);
            Path directory = claim.token() == 1 && !Files.exists(root) ? root : root.resolve("recovery-" + claim.token());
            if (claim.token() > policy.maxRetries() + 1 || System.currentTimeMillis() - claim.startedAt() > elapsedBudget().toMillis()) {
                var stopped = coordinator.unsupported(directory, runId, requirement, new IllegalStateException("Recovery attempt or elapsed budget exhausted"));
                ownership.finish(claim, stopped);
                return stopped;
            }
            reconcilePrior(root, directory, requirement, policy);
            var result = executeLifecycle(runId, requirement, policy, directory);
            if (lost.get()) throw new com.kondapallicb.urlshortener.infrastructure.WorkflowOwnershipStore.StaleWorkerException();
            boolean interrupted = Thread.interrupted();
            try { ownership.finish(claim, result); } finally { if (interrupted) Thread.currentThread().interrupt(); }
            return result;
        } finally {
            scheduler.shutdownNow();
            boolean interrupted = Thread.interrupted();
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException error) { interrupted = true; }
            currentClaim.remove();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    protected Duration elapsedBudget() { return Duration.ofMinutes(10); }
    protected Duration ownershipLease() { return Duration.ofSeconds(30); }
    public void lineage(String parent, String child, String requirement) { ownership.lineage(parent, child, requirement); }
    public java.util.Optional<String> parentRevision(String child) { return ownership.parent(child); }
    private void reconcilePrior(Path root, Path replacement, String requirement, GovernancePolicy policy) {
        if (!Files.exists(root)) return;
        try {
            Path oldPlan = root.resolve("plan.json");
            if (!Files.exists(oldPlan)) throw new IOException("Interrupted workspace lacks approved plan; manual reconciliation required");
            var plan = mapper.readValue(oldPlan.toFile(), EngineeringPlan.class);
            if (!plan.applicablePolicy().equals(policy) || !plan.interpretation().originalText().equals(requirement) || !hash(source).equals(plan.baselineHash())
                    || !hash(root.resolve("baseline")).equals(plan.baselineHash()))
                throw new IOException("Interrupted baseline or requirement is stale");
            Files.createDirectories(replacement);
            var inspected = new ArrayList<java.util.Map<String, Object>>();
            for (var journal : ownership.journals(root.getFileName().toString())) {
                boolean complete = true;
                if (journal.workspace() == null) throw new IOException("Recovery journal lacks workspace");
                for (var entry : journal.entries()) {
                    Path file = GovernedFileService.checked(Path.of(journal.workspace()), entry.path());
                    if (entry.afterHash() == null ? Files.exists(file) : !Files.isRegularFile(file) || !entry.afterHash().equals(GovernedFileService.hash(file))) complete = false;
                }
                inspected.add(java.util.Map.of("journalId", journal.journalId(), "state", journal.state(), "applicationCompleted", complete));
            }
            // Never resume unvalidated output; a new token gets a separate clean workspace.
            writeJson(replacement.resolve("reconciliation.json"), java.util.Map.of("priorWorkspace", root.resolve("workspace").toString(),
                "baselineVerified", true, "decision", "RESTORE_BASELINE_IN_ISOLATED_REPLACEMENT", "incompleteEvidenceInvalidated", true,
                "priorWorkspaceHash", hash(root.resolve("workspace")), "journalsInspected", inspected));
        } catch (IOException invalid) { throw new IllegalStateException("Recovery blocked; no readiness", invalid); }
    }
    private ExecutionEvidence executeLifecycle(String runId, String requirement, GovernancePolicy policy, Path directory) {
        var claim = currentClaim.get();
        var controlledFiles = new GovernedFileService(mapper, journal -> ownership.journal(claim, journal));
        EngineeringPlan plan;
        try { plan = detailedPlan(requirement, policy); }
        catch (RuntimeException unsupported) {
            try { Files.createDirectories(directory); writeJson(directory.resolve("interpretation.json"),
                new RequirementInterpreter(mapper).interpret(requirement, policy.permitRecordedDefaults())); }
            catch (IOException persistenceFailure) { throw new IllegalStateException("Interpretation evidence could not be persisted", persistenceFailure); }
            return coordinator.unsupported(directory, runId, requirement, unsupported);
        }

        Path baseline = directory.resolve("baseline");
        Path workspace = directory.resolve("workspace");
        String outcome = null;
        String artifact = null;
        String artifactHash = null;
        String failure = null;
        String policyPath = directory.resolve("policy-report.json").toString();
        List<FileOperation> operations = List.of();
        var attempts = new ArrayList<ExecutionEvidence.ValidationAttempt>();
        boolean snapshotReady = false;
        boolean verified = false;
        boolean restored = false;
        boolean interrupted = false;
        var lifecycle = coordinator.lifecycle(directory);
        long started = System.nanoTime();
        try {
            Files.createDirectories(directory);
            lifecycle.phase(ExecutionJournal.Phase.PROPOSE);
            writeJson(directory.resolve("interpretation.json"), plan.interpretation());
            ownership.requireOwner(claim);
            writeJson(directory.resolve("plan.json"), plan);
            writeJson(directory.resolve("governance-policy.json"), policy);
            Files.createDirectories(baseline);
            for (String relative : plan.repositoryAnalysis().files()) {
                Path target = baseline.resolve(relative);
                Files.createDirectories(target.getParent());
                copy(source.resolve(relative), target);
            }
            if (!hash(baseline).equals(plan.baselineHash())) throw new IllegalStateException("Repository changed during snapshot; plan is stale");
            snapshotReady = true;
            copy(baseline, workspace);
            operations = operations(plan, implementationAgent);
            writeJson(directory.resolve("original-proposal.encrypted.json"), ownership.archiveProposal(claim, "initial", operations));
            lifecycle.phase(ExecutionJournal.Phase.VALIDATE_PROPOSAL);
            operations = fileService.bind(workspace, operations, plan, runId, false);
            PolicyReport report = policies.evaluate(plan, policy, operations);
            writeJson(Path.of(policyPath), report);
            if (!report.passed()) throw new IllegalStateException("Generated artifacts violate security policy");
            writeJson(directory.resolve("operations.json"), operations);
            lifecycle.phase(ExecutionJournal.Phase.APPLY);
            controlledFiles.apply(workspace, directory.resolve("application"), operations, plan, runId, this::applyOperation);
            List<FileOperation> canonical = operations(plan, new AliasImplementationAgent());
            for (int number = 1; number <= policy.maxRetries() + 2 - claim.token(); number++) {
                if (System.nanoTime() - started > Duration.ofMinutes(10).toNanos()) throw new IllegalStateException("Maximum elapsed execution budget exceeded");
                lifecycle.attempt(number);
                lifecycle.phase(ExecutionJournal.Phase.BUILD);
                Path attemptDirectory = directory.resolve("attempt-" + number);
                Files.createDirectories(attemptDirectory);
                deleteTree(workspace.resolve("target"));
                deleteTree(workspace.resolve(".validation-data"));
                String beforeBuildHash = hash(workspace);
                var attempt = validate(workspace, attemptDirectory);
                attempts.add(attempt);
                if (!hash(workspace).equals(beforeBuildHash)) throw new IOException("Build mutated sources outside the governed application pipeline");
                lifecycle.phase(ExecutionJournal.Phase.PARSE_EVIDENCE);
                var structured = new ValidationEvidenceReader().read(plan, attempt, workspace, false);
                writeJson(attemptDirectory.resolve("validation.json"), structured);
                ownership.validation(claim, number, java.util.Map.of("attempt", attempt, "validation", structured,
                    "build", mapper.readValue(attemptDirectory.resolve("build.json").toFile(), BuildMetadata.class)));
                writeJson(directory.resolve("attempts.json"), attempts);
                if (attempt.cancelled()) { failure = "Validation cancelled"; break; }
                if (attempt.exitCode() == 0 && !attempt.timedOut()) {
                    lifecycle.phase(ExecutionJournal.Phase.PARSE_EVIDENCE);
                    verifyAcceptance(plan, attempt);
                    verified = true;
                    break;
                }
                lifecycle.phase(ExecutionJournal.Phase.DIAGNOSE);
                List<FileOperation> repairs = diagnose(workspace, attempt, canonical);
                writeJson(attemptDirectory.resolve("diagnosis.json"), java.util.Map.of(
                        "exitCode", attempt.exitCode(), "timedOut", attempt.timedOut(),
                        "repairableFiles", repairs.stream().map(FileOperation::path).toList(),
                        "reason", repairs.isEmpty() ? "No bounded repair; safe stop" : "Compiler or acceptance evidence identifies noncanonical generated artifacts"));
                writeJson(attemptDirectory.resolve("recovery-decision.json"), java.util.Map.of("attempt", number,
                    "maxAttempts", policy.maxRetries() + 1, "decision", repairs.isEmpty() || number >= policy.maxRetries() + 2 - claim.token() ? "SAFE_STOP" : "DIAGNOSED_REPAIR"));
                if (repairs.isEmpty() || number > policy.maxRetries()) {
                    failure = "Validation failed: exit=" + attempt.exitCode() + ", timeout=" + attempt.timedOut();
                    break;
                }
                repairs = fileService.bind(workspace, repairs, plan, runId, true);
                writeJson(attemptDirectory.resolve("original-repair.encrypted.json"), ownership.archiveProposal(claim, "repair-" + number, repairs));
                var repairPolicy = policies.evaluate(plan, policy, repairs);
                writeJson(attemptDirectory.resolve("repair-policy.json"), repairPolicy);
                if (!repairPolicy.passed()) throw new IllegalStateException("Repair violates policy");
                writeJson(attemptDirectory.resolve("repair.json"), repairs);
                lifecycle.phase(ExecutionJournal.Phase.REPAIR);
                controlledFiles.apply(workspace, attemptDirectory.resolve("repair-application"), repairs, plan, runId, this::applyOperation);
            }
            outcome = hash(workspace);
            if (verified) {
                Path jar = workspace.resolve("target/url-shortener-agentic-0.0.1-SNAPSHOT.jar");
                checkOutput(workspace, jar);
                if (!Files.isRegularFile(jar)) throw new IOException("Successful validation did not produce the expected artifact");
                Path preserved = directory.resolve("validated-artifact.jar");
                Files.copy(jar, preserved);
                artifact = preserved.toString();
                artifactHash = fileHash(preserved);
            }
            lifecycle.phase(ExecutionJournal.Phase.FINALIZE);
            beforeFinalization(directory);
        } catch (IOException | RuntimeException error) {
            lifecycle.failure(error);
            verified = false;
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            interrupted = Thread.interrupted();
            if (!verified) {
                try {
                    // Restore the entire snapshot, including Maven mutations to copied production files.
                    if (snapshotReady) { restoreBaseline(baseline, workspace, plan.baselineHash()); restored = true; }
                    else { deleteTree(workspace); restored = !Files.exists(workspace); }
                    Files.deleteIfExists(directory.resolve("validated-artifact.jar"));
                    artifact = null;
                    artifactHash = null;
                } catch (IOException rollbackFailure) {
                    lifecycle.rollbackFailure(rollbackFailure);
                    failure = (failure == null ? "" : failure + "; ") + "Rollback failed: " + rollbackFailure.getMessage();
                    restored = false;
                }
            }
        }
        ExecutionEvidence evidence = new ExecutionEvidence(runId, requirement, plan.baselineHash(), outcome,
                workspace.toString(), operations.stream().map(FileOperation::path).toList(), List.copyOf(attempts),
                !verified && restored, verified ? "VALIDATED_NOT_RELEASE_APPROVED" : "SAFE_STOPPED", artifact, artifactHash,
                failure, policyPath, "MACOS_SEATBELT_OFFLINE");
        try {
            writeJson(directory.resolve("evidence.json"), evidence);
            lifecycle.finish(verified, restored, failure, attempts);
        }
        catch (IOException failureToPersist) {
            try { Files.deleteIfExists(directory.resolve("evidence.json")); } catch (IOException ignored) { }
            throw new IllegalStateException("Cannot persist execution evidence; approval forbidden; recoverable application journal retained", failureToPersist);
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        return evidence;
    }

    private List<FileOperation> diagnose(Path workspace, ExecutionEvidence.ValidationAttempt attempt,
            List<FileOperation> canonical) throws IOException {
        if (attempt.timedOut() || attempt.exitCode() < 0) return List.of();
        String log = Files.readString(Path.of(attempt.log()));
        boolean compiler = List.of("invalid method declaration", "cannot find symbol", "';' expected", "illegal start",
                "unclosed string literal", "incompatible types").stream().anyMatch(log::contains);
        boolean testFailure = log.contains("Failures: ") && log.contains("<<< FAILURE!");
        if (!compiler && !testFailure) return List.of();
        var repairs = new ArrayList<FileOperation>();
        for (var operation : canonical) {
            boolean identified = compiler ? log.contains(Path.of(operation.path()).getFileName().toString())
                    : operation.path().startsWith("src/main/java/") && ((log.contains("CustomAliasAcceptanceTest") && operation.path().endsWith("CustomAliasController.java"))
                        || (log.contains("DailyAnalyticsAcceptanceTest") && operation.path().endsWith("DailyAnalyticsController.java")));
            Path target = workspace.resolve(operation.path());
            if (identified && Files.exists(target) && !Files.readString(target).equals(operation.content())) repairs.add(operation);
        }
        return List.copyOf(repairs);
    }

    public ExecutionEvidence evidence(String runId) {
        validId(runId);
        var committed = ownership.completed(runId);
        if (committed.isPresent()) return committed.orElseThrow();
        throw new IllegalStateException("No finalized durable execution evidence for this run");
    }
    public PolicyReport policyReport(String runId) {
        try { return mapper.readValue(Path.of(evidence(runId).policyReport()).toFile(), PolicyReport.class); }
        catch (IOException | RuntimeException failure) { throw new IllegalStateException("No valid security policy evidence", failure); }
    }

    public String approvalHash(WorkflowRun run) {
        String gate = run.pendingApprovals().isEmpty() ? "release-readiness" : run.pendingApprovals().getFirst().name();
        if (gate.equals("release-readiness")) requireReleasePolicy(run);
        return hashForGate(run, gate);
    }
    public void requireReleasePolicy(WorkflowRun run) {
        if (!policyReport(run.runId()).passed()) throw new IllegalStateException("Security policy did not pass");
        if (run.policy().requireSecurityReview()) {
            String expected = hashForGate(run, "security-review");
            boolean reviewed = run.decisions().stream().anyMatch(decision -> decision.decision().equals("APPROVED:security-review")
                    && decision.approval() != null && decision.approval().gate().equals("security-review")
                    && decision.approval().evidenceHash().equals(expected));
            if (!reviewed) throw new IllegalStateException("An authenticated security approval of this exact evidence is required");
        }
    }
    private String hashForGate(WorkflowRun run, String gate) {
        try {
            String input = run.runId() + "\n" + gate + "\n" + mapper.writeValueAsString(run.policy()) + "\n";
            if (gate.equals("architecture-review")) {
                var approvedPlan = run.nodeRuns().get(WorkflowStage.ARCHITECTURE_DESIGN).outputs();
                if (!approvedPlan.equals(plan(run.requirement()))) throw new IllegalStateException("Repository changed; plan must be revised");
                input += mapper.writeValueAsString(detailedPlan(run.requirement(), run.policy()));
            } else {
                ExecutionEvidence evidence = evidence(run.runId());
                if (!evidence.readiness().equals("VALIDATED_NOT_RELEASE_APPROVED") || evidence.buildArtifact() == null
                        || !fileHash(Path.of(evidence.buildArtifact())).equals(evidence.buildArtifactHash())
                        || !hash(Path.of(evidence.workspace())).equals(evidence.outcomeHash())
                        || !hash(source).equals(evidence.baselineHash()) || !policyReport(run.runId()).passed()) {
                    throw new IllegalStateException("Source, policy or outcome changed; evidence is stale");
                }
                input += Files.readString(Path.of(evidence.workspace()).getParent().resolve("terminal.json"));
                input += Files.readString(Path.of(evidence.workspace()).getParent().resolve("operations.json"));
                input += Files.readString(Path.of(evidence.workspace()).getParent().resolve("application/journal.json"));
                input += mapper.writeValueAsString(evidence) + Files.readString(Path.of(evidence.policyReport()))
                        + Files.readString(Path.of(evidence.workspace()).getParent().resolve("plan.json"));
                if (gate.equals("release-readiness")) {
                    input += mapper.writeValueAsString(run.decisions().stream()
                            .filter(decision -> decision.decision().equals("APPROVED:security-review")).toList());
                }
                for (var attempt : evidence.attempts()) {
                    input += Files.readString(Path.of(attempt.log()));
                    Path metadata = Path.of(attempt.log()).getParent();
                    input += Files.readString(metadata.resolve("build.json"));
                    if (attempt.exitCode() == 0) {
                        var fresh = new ValidationEvidenceReader().read(mapper.readValue(evidenceRoot.resolve(run.runId()).resolve("plan.json").toFile(), EngineeringPlan.class), attempt, Path.of(evidence.workspace()), true);
                        var persisted = mapper.readValue(metadata.resolve("validation.json").toFile(), ValidationEvidenceReader.Report.class);
                        if (!fresh.equals(persisted)) throw new IllegalStateException("Structured validation evidence is stale");
                        input += Files.readString(metadata.resolve("validation.json"));
                    }
                    for (String report : attempt.testReports()) input += Files.readString(Path.of(report));
                    if (attempt.coverageReport() != null) input += Files.readString(Path.of(attempt.coverageReport()));
                }
            }
            return digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) { throw new IllegalStateException("Cannot bind approval evidence", failure); }
    }

    private ExecutionEvidence.ValidationAttempt validate(Path workspace, Path directory) throws IOException {
        Path log = directory.resolve("maven.log");
        int exit = -1;
        boolean timedOut = false;
        boolean cancelled = false;
        Process process = null;
        java.time.Instant start = java.time.Instant.now();
        java.util.concurrent.atomic.AtomicBoolean truncated = new java.util.concurrent.atomic.AtomicBoolean();
        Thread pump = null;
        try {
            Path cache = Path.of(System.getProperty("maven.repo.local", System.getProperty("user.home") + "/.m2/repository")).toAbsolutePath();
            var launch = sandbox.maven(workspace, log, maven, cache);
            ProcessBuilder builder = new ProcessBuilder(launch.command()).directory(workspace.toFile())
                    .redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(launch.environment());
            process = startBuild(builder);
            final Process runningProcess = process;
            pump = new Thread(() -> {
                try (var input = runningProcess.getInputStream(); var output = Files.newOutputStream(log)) {
                    byte[] buffer = new byte[8192]; int read; long retained = 0;
                    while ((read = input.read(buffer)) != -1) {
                        int accepted = (int) Math.min(read, Math.max(0, 2097152 - retained));
                        if (accepted < read) truncated.set(true);
                        output.write(buffer, 0, accepted); retained += accepted;
                    }
                } catch (IOException failure) { truncated.set(true); }
            }, "bounded-maven-output");
            pump.start();
            if (!process.waitFor(Math.max(1, Math.min(validationTimeout().toMillis(),
                    elapsedBudget().toMillis() - (System.currentTimeMillis() - currentClaim.get().startedAt()))), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                kill(process);
            } else exit = process.exitValue();
        } catch (InterruptedException interrupted) {
            if (process != null) kill(process);
            cancelled = true;
        } catch (IOException unavailable) {
            Files.writeString(log, "Sandboxed Maven could not start: " + unavailable.getMessage());
        } finally {
            if (process != null && process.isAlive()) kill(process);
            if (pump != null) {
                boolean interrupted = Thread.interrupted();
                try { pump.join(5000); } catch (InterruptedException error) { interrupted = true; }
                if (pump.isAlive()) throw new IOException("Build output capture did not terminate");
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        List<String> reports = new ArrayList<>();
        Path surefire = workspace.resolve("target/surefire-reports");
        if (Files.isDirectory(surefire)) {
            checkOutput(workspace, surefire);
            Path preserved = directory.resolve("test-reports");
            copy(surefire, preserved);
            try (var files = Files.list(preserved)) {
                reports.addAll(files.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
                        .map(Path::toString).sorted().toList());
            }
        }
        Path coverage = workspace.resolve("target/site/jacoco/jacoco.xml");
        String coverageReport = null;
        if (Files.isRegularFile(coverage)) {
            checkOutput(workspace, coverage);
            Path preserved = directory.resolve("jacoco.xml");
            Files.copy(coverage, preserved);
            coverageReport = preserved.toString();
        }
        java.time.Instant end = java.time.Instant.now();
        String output = Files.exists(log) ? Files.readString(log) : "";
        String version = output.lines().filter(line -> line.contains("Apache Maven")).findFirst().orElse("UNAVAILABLE");
        writeJson(directory.resolve("build.json"), new BuildMetadata("MAVEN_CLEAN_VERIFY_OFFLINE", version, start.toString(), end.toString(),
            Duration.between(start, end).toMillis(), exit, timedOut, log.toString(), "", true, truncated.get()));
        if (cancelled) Thread.currentThread().interrupt();
        return new ExecutionEvidence.ValidationAttempt(exit, timedOut, log.toString(), List.copyOf(reports), coverageReport, cancelled);
    }
    public record BuildMetadata(String capability, String toolVersion, String start, String end, long durationMillis,
        int exitCode, boolean timedOut, String stdout, String stderr, boolean streamsMerged, boolean truncated) { }
    protected Process startBuild(ProcessBuilder builder) throws IOException { return builder.start(); }
    protected Duration validationTimeout() { return Duration.ofMinutes(5); }
    private void kill(Process process) {
        boolean interrupted = Thread.interrupted();
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
            for (var descendant : descendants) {
                if (descendant.isAlive()) descendant.onExit().get(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException cancellation) { interrupted = true; }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException stillAlive) {
            throw new IllegalStateException("Worker termination failed; no approval is permitted", stillAlive);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    protected void verifyAcceptance(EngineeringPlan plan, ExecutionEvidence.ValidationAttempt attempt) throws IOException {
        Path workspace = Path.of(attempt.log()).getParent().getParent().resolve("workspace");
        var build = mapper.readValue(Path.of(attempt.log()).getParent().resolve("build.json").toFile(), BuildMetadata.class);
        if (!build.capability().equals("MAVEN_CLEAN_VERIFY_OFFLINE") || build.toolVersion().equals("UNAVAILABLE")
                || build.exitCode() != 0 || build.timedOut()) throw new IOException("Invalid controlled build metadata");
        var report = new ValidationEvidenceReader().read(plan, attempt, workspace, true);
        writeJson(Path.of(attempt.log()).getParent().resolve("validation.json"), report);
    }

    protected void applyOperation(Path workspace, FileOperation operation) throws IOException {
        var claim = currentClaim.get();
        if (claim != null) ownership.requireOwner(claim);
        GovernedFileService.applyOne(workspace, operation);
    }
    protected void restoreBaseline(Path baseline, Path workspace, String expectedHash) throws IOException {
        deleteTree(workspace);
        copy(baseline, workspace);
        if (!hash(workspace).equals(expectedHash)) throw new IOException("Restored snapshot hash mismatch");
    }
    protected void beforeFinalization(Path directory) throws IOException { }
    protected void writeJson(Path path, Object value) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "evidence-", ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private void copy(Path from, Path to) throws IOException {
        if (Files.isSymbolicLink(from)) throw new IOException("Symlinks are forbidden in execution input");
        if (Files.isDirectory(from)) {
            Files.createDirectories(to);
            try (var children = Files.list(from)) { for (Path child : children.toList()) copy(child, to.resolve(child.getFileName())); }
        } else Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
    }
    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    private void validId(String runId) {
        if (runId == null || !runId.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid run ID");
    }
    private String hash(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = new ArrayList<>();
            for (String name : List.of("pom.xml", "mvnw", "README.md")) if (Files.exists(root.resolve(name))) files.add(root.resolve(name));
            if (Files.exists(root.resolve(".mvn"))) {
                checkOutput(root, root.resolve(".mvn"));
                try (var paths = Files.walk(root.resolve(".mvn"))) { files.addAll(paths.filter(Files::isRegularFile).sorted().toList()); }
            }
            if (Files.exists(root.resolve("GENERATED_README.md"))) files.add(root.resolve("GENERATED_README.md"));
            if (Files.exists(root.resolve("src"))) {
                checkOutput(root, root.resolve("src"));
                try (var paths = Files.walk(root.resolve("src"))) { files.addAll(paths.filter(Files::isRegularFile).sorted().toList()); }
            }
            for (Path file : files) {
                checkOutput(root, file);
                digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private String fileHash(Path file) throws IOException { return digest(Files.readAllBytes(file)); }

    static void checkOutput(Path root, Path file) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot) || Files.isSymbolicLink(normalizedRoot)) {
            throw new IOException("Artifact escapes workspace");
        }
        Path cursor = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalizedFile)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symlink in workspace artifact path");
        }
        if (!normalizedFile.toRealPath().startsWith(normalizedRoot.toRealPath())) throw new IOException("Artifact escapes workspace");
    }
}
