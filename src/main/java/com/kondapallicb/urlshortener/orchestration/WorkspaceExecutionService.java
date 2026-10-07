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
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceExecutionService {
    private final Path source;
    private final Path evidenceRoot;
    private final String maven;
    private final ObjectMapper mapper;
    private final AliasImplementationAgent implementationAgent;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceExecutionService(@Value("${app.execution.repository:.}") String source,
            @Value("${app.execution.evidence:./workflow-evidence}") String evidenceRoot,
            @Value("${app.execution.maven:mvn}") String maven, ObjectMapper mapper) {
        this(source, evidenceRoot, maven, mapper, new AliasImplementationAgent());
    }

    WorkspaceExecutionService(String source, String evidenceRoot, String maven, ObjectMapper mapper,
            AliasImplementationAgent implementationAgent) {
        this.source = Path.of(source).toAbsolutePath().normalize();
        this.evidenceRoot = Path.of(evidenceRoot).toAbsolutePath().normalize();
        this.maven = maven;
        this.mapper = mapper;
        this.implementationAgent = implementationAgent;
    }

    public boolean supports(String requirement) {
        String normalized = requirement.toLowerCase(Locale.ROOT);
        return normalized.contains("custom alias") && !normalized.contains("brand")
                && !normalized.contains("domain") && !normalized.contains("tenant");
    }

    public List<String> plan(String requirement) {
        if (!supports(requirement)) {
            return List.of("Clarification required: supported capability is custom aliases on the existing domain.",
                    "Specify alias format, collision behavior, TTL and ownership before extending this capability.");
        }
        try {
            if (!Files.readString(source.resolve("src/main/java/com/kondapallicb/urlshortener/api/UrlController.java"))
                    .contains("resolveAndRecordClick")) {
                throw new IllegalStateException("Repository has no compatible redirect integration");
            }
            return List.of("Baseline SHA-256: " + hash(source),
                    "Add POST /api/aliases accepting a 3-64 character alphanumeric, underscore or hyphen alias.",
                    "Persist through UrlMappingRepository; duplicate and reserved aliases return 409.",
                    "Prove existing redirect resolves the alias; test collision immutability and invalid aliases.",
                    "Execute mvn verify with Surefire and JaCoCo evidence; release remains blocked without authenticated approval.");
        } catch (IOException exception) {
            throw new IllegalStateException("Repository analysis failed", exception);
        }
    }

    public synchronized ExecutionEvidence execute(String runId, String requirement) {
        if (!runId.matches("[a-zA-Z0-9-]+") || !supports(requirement)) {
            throw new IllegalArgumentException("Unsupported execution request");
        }
        Path directory = evidenceRoot.resolve(runId);
        Path workspace = directory.resolve("workspace");
        try {
            if (Files.exists(directory)) throw new IllegalStateException("Execution already exists");
            Files.createDirectories(workspace);
            copy(source.resolve("pom.xml"), workspace.resolve("pom.xml"));
            copy(source.resolve("src"), workspace.resolve("src"));
            String baseline = hash(workspace);
            List<FileOperation> operations = new ArrayList<>(implementationAgent.implement());
            operations.addAll(new AliasTestingAgent().tests());
            for (FileOperation operation : operations) apply(workspace, operation);
            Files.writeString(directory.resolve("operations.json"), mapper.writeValueAsString(operations));
            List<ExecutionEvidence.ValidationAttempt> attempts = new ArrayList<>();
            Path firstAttempt = directory.resolve("attempt-1");
            Files.createDirectories(firstAttempt);
            attempts.add(validate(workspace, firstAttempt));
            var first = attempts.getFirst();
            String diagnostic = Files.readString(Path.of(first.log()));
            if (first.exitCode() != 0 && !first.timedOut()
                    && diagnostic.contains("CustomAliasController.java")
                    && diagnostic.contains("invalid method declaration; return type required")) {
                // One diagnosed constructor-name repair is allowed; unknown failures stop.
                for (FileOperation repair : new AliasImplementationAgent().implement()) {
                    Files.writeString(workspace.resolve(repair.path()), repair.content());
                }
                Files.writeString(directory.resolve("repair.json"), mapper.writeValueAsString(
                        new AliasImplementationAgent().implement()));
                Path retry = directory.resolve("attempt-2");
                Files.createDirectories(retry);
                attempts.add(validate(workspace, retry));
            }
            ExecutionEvidence.ValidationAttempt validation = attempts.getLast();
            boolean verified = validation.exitCode() == 0 && !validation.timedOut()
                    && validation.testReports().stream().anyMatch(path -> path.endsWith("CustomAliasAcceptanceTest.xml"))
                    && validation.coverageReport() != null && acceptancePassed(validation);
            String outcome = hash(workspace);
            String artifact = null;
            String artifactHash = null;
            if (verified) {
                Path jar = workspace.resolve("target/url-shortener-agentic-0.0.1-SNAPSHOT.jar");
                if (!Files.isRegularFile(jar)) verified = false;
                else {
                    Path preserved = directory.resolve("validated-artifact.jar");
                    Files.copy(jar, preserved);
                    artifact = preserved.toString();
                    artifactHash = fileHash(preserved);
                }
            }
            if (!verified) {
                // Remove only agent-created artifacts. The copied repository is the rollback point.
                for (FileOperation operation : operations) Files.deleteIfExists(workspace.resolve(operation.path()));
                if (!baseline.equals(hash(workspace))) throw new IllegalStateException("Rollback hash mismatch");
            }
            ExecutionEvidence evidence = new ExecutionEvidence(runId, requirement, baseline, outcome,
                    workspace.toString(), operations.stream().map(FileOperation::path).toList(), attempts,
                    !verified, verified ? "VALIDATED_NOT_RELEASE_APPROVED" : "SAFE_STOPPED", artifact, artifactHash);
            Files.writeString(directory.resolve("evidence.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            return evidence;
        } catch (IOException exception) {
            throw new IllegalStateException("Execution failed; no release permitted", exception);
        }
    }

    public ExecutionEvidence evidence(String runId) {
        if (!runId.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid run ID");
        try {
            return mapper.readValue(evidenceRoot.resolve(runId).resolve("evidence.json").toFile(), ExecutionEvidence.class);
        } catch (IOException exception) {
            throw new IllegalStateException("No durable execution evidence for this run", exception);
        }
    }

    public String approvalHash(WorkflowRun run) {
        try {
            String input;
            if (run.pendingApprovals().stream().anyMatch(gate -> gate.name().equals("architecture-review"))) {
                var approvedPlan = run.nodeRuns().get(WorkflowStage.ARCHITECTURE_DESIGN).outputs();
                if (!approvedPlan.equals(plan(run.requirement()))) throw new IllegalStateException("Repository changed; plan must be revised");
                input = run.runId() + "\n" + run.requirement() + "\n" + String.join("\n", approvedPlan);
            } else {
                ExecutionEvidence evidence = evidence(run.runId());
                if (!evidence.readiness().equals("VALIDATED_NOT_RELEASE_APPROVED")
                        || evidence.buildArtifact() == null
                        || !fileHash(Path.of(evidence.buildArtifact())).equals(evidence.buildArtifactHash())
                        || !hash(Path.of(evidence.workspace())).equals(evidence.outcomeHash())
                        || !hash(source).equals(evidence.baselineHash())) {
                    throw new IllegalStateException("Source or outcome changed; validation and approval are stale");
                }
                input = mapper.writeValueAsString(evidence);
                for (var attempt : evidence.attempts()) {
                    input += Files.readString(Path.of(attempt.log()));
                    for (String report : attempt.testReports()) input += Files.readString(Path.of(report));
                    if (attempt.coverageReport() != null) input += Files.readString(Path.of(attempt.coverageReport()));
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot bind approval evidence", failure);
        }
    }

    private ExecutionEvidence.ValidationAttempt validate(Path workspace, Path directory) throws IOException {
        Path log = directory.resolve("maven.log");
        int exit = -1;
        boolean timedOut = false;
        Process process = null;
        try {
            process = new ProcessBuilder(maven, "--batch-mode", "--no-transfer-progress", "verify",
                    "-Dagent.execution.child=true", "-Dmaven.repo.local=" + System.getProperty("maven.repo.local",
                            System.getProperty("user.home") + "/.m2/repository")).directory(workspace.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            } else exit = process.exitValue();
        } catch (InterruptedException exception) {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new IOException("Validation interrupted", exception);
        } catch (IOException exception) {
            Files.writeString(log, "Maven could not start: " + exception.getMessage());
        }
        List<String> reports = new ArrayList<>();
        Path surefire = workspace.resolve("target/surefire-reports");
        if (Files.isDirectory(surefire)) {
            Path preserved = directory.resolve("test-reports");
            copy(surefire, preserved);
            try (var files = Files.list(preserved)) {
                reports.addAll(files.filter(p -> p.getFileName().toString().startsWith("TEST-")
                        && p.toString().endsWith(".xml")).map(Path::toString).sorted().toList());
            }
        }
        Path coverage = workspace.resolve("target/site/jacoco/jacoco.xml");
        String coverageReport = null;
        if (Files.isRegularFile(coverage)) {
            Path preserved = directory.resolve("jacoco.xml");
            Files.copy(coverage, preserved);
            coverageReport = preserved.toString();
        }
        return new ExecutionEvidence.ValidationAttempt(exit, timedOut, log.toString(), reports, coverageReport);
    }

    private boolean acceptancePassed(ExecutionEvidence.ValidationAttempt attempt) throws IOException {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            String report = attempt.testReports().stream().filter(path -> path.endsWith("CustomAliasAcceptanceTest.xml"))
                    .findFirst().orElseThrow();
            var suite = factory.newDocumentBuilder().parse(Path.of(report).toFile()).getDocumentElement();
            return Integer.parseInt(suite.getAttribute("tests")) >= 2
                    && Integer.parseInt(suite.getAttribute("failures")) == 0
                    && Integer.parseInt(suite.getAttribute("errors")) == 0
                    && Integer.parseInt(suite.getAttribute("skipped")) == 0;
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | RuntimeException exception) {
            throw new IOException("Invalid validation evidence", exception);
        }
    }

    private void apply(Path workspace, FileOperation operation) throws IOException {
        Path target = workspace.resolve(operation.path()).normalize();
        if (!target.startsWith(workspace.resolve("src")) || !target.toString().endsWith(".java")
                || Files.exists(target)) throw new IllegalArgumentException("File operation violates create-only policy");
        Files.createDirectories(target.getParent());
        Files.writeString(target, operation.content(), StandardCharsets.UTF_8);
    }

    private void copy(Path from, Path to) throws IOException {
        if (Files.isSymbolicLink(from)) throw new IOException("Symlinks are forbidden in execution input");
        if (Files.isDirectory(from)) {
            Files.createDirectories(to);
            try (var children = Files.list(from)) {
                for (Path child : children.toList()) copy(child, to.resolve(child.getFileName()));
            }
        } else Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private String hash(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = new ArrayList<>();
            files.add(root.resolve("pom.xml"));
            try (var paths = Files.walk(root.resolve("src"))) {
                files.addAll(paths.filter(Files::isRegularFile).sorted().toList());
            }
            for (Path file : files) {
                if (Files.isSymbolicLink(file)) throw new IOException("Symlink input");
                digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String fileHash(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
