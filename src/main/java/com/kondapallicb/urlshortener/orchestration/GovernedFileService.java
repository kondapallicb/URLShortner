package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

public class GovernedFileService {
    public record Entry(String path, FileOperation.Type type, String beforeHash, String afterHash, String backup) { }
    public record Journal(String revision, String state, List<Entry> entries, String detail, String journalId, String workspace) {
        public Journal(String revision, String state, List<Entry> entries, String detail) { this(revision, state, entries, detail, "legacy", null); }
    }
    public record ValidationResult(boolean valid, String failure, int count, long bytes) { }
    @FunctionalInterface public interface Applier { void apply(Path root, FileOperation operation) throws IOException; }
    private final ObjectMapper mapper;
    private final java.util.function.Consumer<Journal> recorder;
    public GovernedFileService(ObjectMapper mapper) { this(mapper, journal -> { }); }
    public GovernedFileService(ObjectMapper mapper, java.util.function.Consumer<Journal> recorder) { this.mapper = mapper; this.recorder = recorder; }

    public List<FileOperation> bind(Path root, List<FileOperation> proposal, EngineeringPlan plan, String revision, boolean repair) throws IOException {
        var result = new ArrayList<FileOperation>();
        for (var operation : proposal) {
            if (operation.revisionId() != null) { result.add(operation); continue; }
            Path target = checked(root, operation.path());
            result.add(operation.bind(plan, revision, repair && Files.isRegularFile(target) ? hash(target) : null));
        }
        return List.copyOf(result);
    }
    public void validate(Path root, List<FileOperation> operations, EngineeringPlan plan, String revision) throws IOException {
        if (operations.isEmpty() || operations.size() > 100) throw new IllegalArgumentException("File count limit 1..100");
        var paths = new HashSet<Path>();
        var ids = new HashSet<String>();
        long total = 0;
        var expectedInputs = FileOperation.inputs(plan);
        for (var op : operations) {
            Path target = checked(root, op.path());
            if (!paths.add(target) || op.operationId() == null || !ids.add(op.operationId())) throw new IllegalArgumentException("Duplicate path or operation ID");
            if (!op.path().matches(".*\\.(java|xml|md|yml|yaml|properties|txt)$") && !op.path().equals("src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker")) throw new IllegalArgumentException("Forbidden file type");
            if (op.type() == null || (op.type() != FileOperation.Type.DELETE && op.content() == null)) throw new IllegalArgumentException("Complete content required");
            long size = op.content() == null ? 0 : op.content().getBytes(StandardCharsets.UTF_8).length;
            total += size;
            if (size > 1048576 || total > 5242880) throw new IllegalArgumentException("Proposal size limit exceeded");
            var task = plan.tasks().stream().filter(t -> t.id().equals(op.taskId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown task"));
            if (!task.writeScope().contains(op.path())) throw new IllegalArgumentException("Out-of-scope write");
            if (!revision.equals(op.revisionId()) || !task.requirementIds().equals(op.requirementIds())
                    || !task.criterionIds().equals(op.criterionIds()) || !expectedInputs.equals(op.inputArtifactHashes()))
                throw new StaleInputException("Traceability or input hashes differ from current revision");
            if (op.reason() == null || op.reason().isBlank()) throw new IllegalArgumentException("Operation reason required");
            if (op.type() == FileOperation.Type.CREATE) {
                if (!op.expectedAbsence() || op.expectedBeforeHash() != null || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new StaleInputException("CREATE target exists");
            } else {
                if (op.expectedAbsence() || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || op.expectedBeforeHash() == null
                        || !op.expectedBeforeHash().equals(hash(target))) throw new StaleInputException("Expected before hash mismatch");
            }
        }
    }
    public Journal apply(Path root, Path audit, List<FileOperation> operations, EngineeringPlan plan, String revision, Applier applier) throws IOException {
        Files.createDirectories(audit);
        persist(audit.resolve("proposal.json"), operations);
        try { validate(root, operations, plan, revision); }
        catch (IOException | RuntimeException invalid) {
            persist(audit.resolve("validation.json"), new ValidationResult(false, invalid.toString(), operations.size(), 0));
            throw invalid;
        }
        persist(audit.resolve("validation.json"), new ValidationResult(true, null, operations.size(),
            operations.stream().mapToLong(o -> o.content() == null ? 0 : o.content().getBytes(StandardCharsets.UTF_8).length).sum()));
        var entries = new ArrayList<Entry>();
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < operations.size(); i++) {
            var op = operations.get(i);
            Path target = checked(root, op.path());
            Path backup = audit.resolve("backup/" + i);
            String before = Files.exists(target) ? Files.readString(target) : "";
            if (op.type() != FileOperation.Type.CREATE) { Files.createDirectories(backup.getParent()); Files.copy(target, backup); force(backup); }
            if (op.type() != FileOperation.Type.DELETE) {
                Path staged = audit.resolve("staged/" + i);
                Files.createDirectories(staged.getParent()); Files.writeString(staged, op.content()); force(staged);
            }
            entries.add(new Entry(op.path(), op.type(), op.expectedBeforeHash(),
                op.type() == FileOperation.Type.DELETE ? null : RequirementInterpreter.hash(op.content()),
                op.type() == FileOperation.Type.CREATE ? null : backup.toString()));
            diff.append("--- a/").append(op.path()).append("\n+++ b/").append(op.path()).append("\n")
                .append("@@ -1,").append(before.lines().count()).append(" +1,").append(op.content() == null ? 0 : op.content().lines().count()).append(" @@\n");
            before.lines().forEach(line -> diff.append("-").append(line).append("\n"));
            if (op.content() != null) op.content().lines().forEach(line -> diff.append("+").append(line).append("\n"));
        }
        Files.writeString(audit.resolve("changes.diff"), diff); force(audit.resolve("changes.diff"));
        Journal journal = new Journal(revision, "PREPARED", List.copyOf(entries), null, RequirementInterpreter.hash(audit.toAbsolutePath().toString()), root.toAbsolutePath().toString());
        persist(audit.resolve("journal.json"), journal);
        try {
            validate(root, operations, plan, revision);
            persist(audit.resolve("journal.json"), new Journal(revision, "APPLYING", entries, null, journal.journalId(), journal.workspace()));
            for (var op : operations) applier.apply(root, op);
            for (var entry : entries) {
                Path file = checked(root, entry.path());
                if (entry.afterHash() == null ? Files.exists(file) : !entry.afterHash().equals(hash(file)))
                    throw new IOException("After hash mismatch: " + entry.path());
            }
            journal = new Journal(revision, "APPLIED", entries, null, journal.journalId(), journal.workspace());
            persist(audit.resolve("journal.json"), journal);
            return journal;
        } catch (IOException | RuntimeException failure) {
            try { rollback(root, audit, journal); }
            catch (IOException rollback) {
                persist(audit.resolve("journal.json"), new Journal(revision, "ROLLBACK_FAILED", entries, rollback.toString(), journal.journalId(), journal.workspace()));
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }
    public void rollback(Path root, Path audit, Journal journal) throws IOException {
        for (var entry : journal.entries()) {
            Path file = checked(root, entry.path());
            if (entry.beforeHash() == null) Files.deleteIfExists(file);
            else {
                Path backup = Path.of(entry.backup());
                if (!hash(backup).equals(entry.beforeHash())) throw new IOException("Backup integrity failure");
                atomic(file, Files.readString(backup));
            }
        }
        for (var entry : journal.entries()) {
            Path file = checked(root, entry.path());
            if (entry.beforeHash() == null ? Files.exists(file) : !entry.beforeHash().equals(hash(file)))
                throw new IOException("Rollback verification failed");
        }
        persist(audit.resolve("journal.json"), new Journal(journal.revision(), "ROLLED_BACK", journal.entries(), null, journal.journalId(), journal.workspace()));
    }
    public void reconcile(Path root, Path audit) throws IOException {
        Path path = audit.resolve("journal.json");
        if (!Files.exists(path)) return;
        Journal journal = mapper.readValue(path.toFile(), Journal.class);
        if (!journal.state().equals("ROLLED_BACK")) rollback(root, audit, journal);
    }
    public static void applyOne(Path root, FileOperation op) throws IOException {
        Path path = checked(root, op.path());
        if (op.type() == FileOperation.Type.DELETE) Files.delete(path);
        else atomic(path, op.content());
    }
    public static Path checked(Path root, String path) throws IOException {
        Path relative = Path.of(path);
        if (relative.isAbsolute() || relative.toString().contains("\\") || relative.toString().isBlank()
                || relative.iterator().hasNext() && java.util.stream.StreamSupport.stream(relative.spliterator(), false).anyMatch(p -> p.toString().equals("..")))
            throw new IllegalArgumentException("Absolute/traversal path forbidden");
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path target = absoluteRoot.resolve(relative).normalize();
        if (!target.startsWith(absoluteRoot) || Files.isSymbolicLink(absoluteRoot)) throw new IOException("Path escapes root");
        Path cursor = absoluteRoot;
        for (Path component : absoluteRoot.relativize(target)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symlink path forbidden");
        }
        return target;
    }
    public void persist(Path path, Object value) throws IOException {
        atomic(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        if (value instanceof Journal journal) recorder.accept(journal);
    }
    public static void atomic(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".stage-", ".tmp");
        try {
            Files.writeString(temp, content); force(temp);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    public static String hash(Path path) throws IOException { return RequirementInterpreter.hash(Files.readString(path)); }
    public static class StaleInputException extends IllegalStateException {
        public StaleInputException(String message) { super(message); }
    }
}
