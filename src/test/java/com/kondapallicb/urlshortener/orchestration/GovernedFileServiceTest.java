package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class GovernedFileServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GovernedFileService files = new GovernedFileService(mapper);
    private EngineeringPlan plan(Path repository) throws Exception {
        return new RequirementPlanner(mapper).analyze(RequirementSpec.aliases(), repository, "baseline");
    }
    @Test void deleteRequiresCurrentHashAndCanBeRecoveredFromItsBackup() throws Exception {
        var plan = plan(Files.createDirectory(directory.resolve("repo")));
        Path root = Files.createDirectory(directory.resolve("workspace"));
        Path target = root.resolve("pom.xml");
        Files.writeString(target, "original build configuration");
        var intent = new FileOperation(null, FileOperation.Type.DELETE, "pom.xml", null, null, false,
            "Replace obsolete build configuration", List.of(), List.of(), null, null, java.util.Map.of());
        var proposal = files.bind(root, List.of(intent), plan, "r1", true);
        Path audit = directory.resolve("delete-audit");
        var journal = files.apply(root, audit, proposal, plan, "r1", GovernedFileService::applyOne);
        assertThat(Files.exists(target)).isFalse();
        assertThat(journal.state()).isEqualTo("APPLIED");
        files.rollback(root, audit, journal);
        assertThat(Files.readString(target)).isEqualTo("original build configuration");
        assertThat(GovernedFileService.hash(target)).isEqualTo(proposal.getFirst().expectedBeforeHash());
        Files.writeString(target, "changed after proposal");
        assertThatThrownBy(() -> files.validate(root, proposal, plan, "r1"))
            .isInstanceOf(GovernedFileService.StaleInputException.class);
        assertThat(Files.readString(target)).isEqualTo("changed after proposal");
    }
    @Test void rejectsTraversalSymlinksDuplicatesOversizeAndWrongTaskBeforeMutation() throws Exception {
        Path repository = Files.createDirectory(directory.resolve("repo"));
        var plan = plan(repository);
        Path root = Files.createDirectory(directory.resolve("workspace"));
        var raw = new GreenfieldAgent().scaffold().getFirst();
        var valid = files.bind(root, List.of(raw), plan, "r1", false).getFirst();
        assertThatThrownBy(() -> files.bind(root, List.of(new FileOperation("../escape.java", "bad")), plan, "r1", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.bind(root, List.of(new FileOperation("/tmp/escape.java", "bad")), plan, "r1", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.validate(root, List.of(valid, valid), plan, "r1")).isInstanceOf(IllegalArgumentException.class);
        var oversized = files.bind(root, List.of(new FileOperation("pom.xml", "x".repeat(1048577))), plan, "r1", false);
        assertThatThrownBy(() -> files.validate(root, oversized, plan, "r1")).isInstanceOf(IllegalArgumentException.class);
        var wrong = new FileOperation(valid.operationId(), valid.type(), "outside.java", "bad", null, true, valid.reason(),
            valid.requirementIds(), valid.criterionIds(), valid.taskId(), valid.revisionId(), valid.inputArtifactHashes());
        assertThatThrownBy(() -> files.validate(root, List.of(wrong), plan, "r1")).isInstanceOf(IllegalArgumentException.class);
        var stale = new FileOperation(valid.operationId(), valid.type(), valid.path(), valid.content(), null, true, valid.reason(),
            valid.requirementIds(), valid.criterionIds(), valid.taskId(), "old-revision", valid.inputArtifactHashes());
        assertThatThrownBy(() -> files.validate(root, List.of(stale), plan, "r1")).isInstanceOf(GovernedFileService.StaleInputException.class);
        Files.createSymbolicLink(root.resolve("src"), directory);
        assertThatThrownBy(() -> files.bind(root, new AliasImplementationAgent().implement(), plan, "r1", false)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(root.resolve("pom.xml"))).isFalse();
        assertThat(Files.exists(directory.resolve("escape.java"))).isFalse();
    }
    @Test void staleUpdateHashAndCreateTargetAreRejectedAndMidwayFailureRestoresEveryHash() throws Exception {
        Path repository = Files.createDirectory(directory.resolve("repo"));
        var plan = plan(repository);
        Path root = Files.createDirectory(directory.resolve("workspace"));
        var proposal = files.bind(root, new GreenfieldAgent().scaffold().subList(0, 2), plan, "r1", false);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        Path audit = directory.resolve("audit");
        assertThatThrownBy(() -> files.apply(root, audit, proposal, plan, "r1", (workspace, op) -> {
            if (count.incrementAndGet() == 2) throw new java.io.IOException("Injected application failure");
            GovernedFileService.applyOne(workspace, op);
        })).isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(root.resolve("pom.xml"))).isFalse();
        assertThat(Files.readString(audit.resolve("journal.json"))).contains("ROLLED_BACK");
        assertThat(Files.readString(audit.resolve("changes.diff"))).contains("--- a/pom.xml");
        Files.writeString(root.resolve("pom.xml"), "original");
        var updates = files.bind(root, List.of(new FileOperation("pom.xml", "revised")), plan, "r1", true);
        Files.writeString(root.resolve("pom.xml"), "concurrent-change");
        assertThatThrownBy(() -> files.validate(root, updates, plan, "r1")).isInstanceOf(GovernedFileService.StaleInputException.class);
        assertThatThrownBy(() -> files.validate(root, proposal, plan, "r1")).isInstanceOf(GovernedFileService.StaleInputException.class);
        var corrected = files.bind(root, new GreenfieldAgent().scaffold().subList(0, 2), plan, "r1", true);
        assertThatThrownBy(() -> files.apply(root, directory.resolve("update-audit"), corrected, plan, "r1", (workspace, op) -> {
            if (op.path().endsWith("UrlShortenerApplication.java")) throw new java.io.IOException("Injected second operation");
            GovernedFileService.applyOne(workspace, op);
        })).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(root.resolve("pom.xml"))).isEqualTo("concurrent-change");
    }
}
