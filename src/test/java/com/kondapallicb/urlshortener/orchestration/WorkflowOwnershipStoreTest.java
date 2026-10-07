package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.infrastructure.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WorkflowOwnershipStoreTest {
    @TempDir Path directory;
    private DataSource database() {
        String url = System.getProperty("workflow.test.jdbc");
        if (url == null) return WorkflowOwnershipStore.local(directory.toString());
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "workflow_test", "");
    }
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ExecutionEvidence result(String id) {
        return new ExecutionEvidence(id, "requirement", null, null, null, List.of(), List.of(), true, "SAFE_STOPPED", null, null);
    }
    @Test void onlyOneWorkerClaimsAndExpiredWorkerCannotFinalizeOrJournal() throws Exception {
        var db = database();
        var first = new WorkflowOwnershipStore(db, mapper);
        var second = new WorkflowOwnershipStore(db, mapper);
        String id = UUID.randomUUID().toString();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.await(); return first.claim(id, "worker-a", "requirement", Duration.ofMillis(500)); });
            var b = executor.submit(() -> { ready.await(); return second.claim(id, "worker-b", "requirement", Duration.ofMillis(500)); });
            ready.countDown();
            var ca = a.get(); var cb = b.get();
            assertThat(ca.isPresent() ^ cb.isPresent()).isTrue();
            var old = ca.orElseGet(cb::orElseThrow);
            Thread.sleep(600);
            var replacement = second.claim(id, "replacement", "requirement", Duration.ofSeconds(5)).orElseThrow();
            assertThat(replacement.token()).isGreaterThan(old.token());
            assertThatThrownBy(() -> first.finish(old, result(id))).isInstanceOf(WorkflowOwnershipStore.StaleWorkerException.class);
            assertThatThrownBy(() -> first.journal(old, new GovernedFileService.Journal(id, "APPLIED", List.of(), null)))
                .isInstanceOf(WorkflowOwnershipStore.StaleWorkerException.class);
            second.finish(replacement, result(id));
            second.finish(replacement, result(id));
            assertThat(first.completed(id).orElseThrow()).isEqualTo(result(id));
            assertThat(first.claim(id, "third", "requirement", Duration.ofSeconds(5))).isEmpty();
            assertThat(new WorkflowOwnershipStore(db, mapper).attempts(id)).hasSize(2);
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void heartbeatsPreventTakeoverAndVersionedWorkflowApprovalsSurviveRestart() throws Exception {
        var db = database();
        var ownership = new WorkflowOwnershipStore(db, mapper);
        String id = UUID.randomUUID().toString();
        var claim = ownership.claim(id, "worker", "requirement", Duration.ofSeconds(10)).orElseThrow();
        ownership.heartbeat(claim, Duration.ofSeconds(30));
        var archive = ownership.archiveProposal(claim, "initial", List.of(new FileOperation("source.java", "synthetic sensitive proposal")));
        assertThat(archive.ciphertext()).doesNotContain("sensitive");
        assertThat(new WorkflowOwnershipStore(db, mapper).decryptProposal(archive)).contains("synthetic sensitive proposal");
        assertThat(ownership.claim(id, "competitor", "requirement", Duration.ofSeconds(5))).isEmpty();
        var repository = new DurableWorkflowRunRepository(db, mapper);
        var now = java.time.Instant.now();
        var run = new WorkflowRun(id, WorkflowScenario.BROWNFIELD, "requirement", new DefaultScenarioCatalog().all().getFirst(),
            ExecutionState.WAITING_FOR_APPROVAL, WorkflowGraph.defaultGraph(), GovernancePolicy.defaultPolicy(), Map.of(),
            List.of(new DecisionRecord(now, WorkflowStage.ARCHITECTURE_DESIGN, "APPROVED:architecture-review", "reviewed",
                new DecisionRecord.ApprovalAudit("architecture-review", "operator", "evidence-hash"))), List.of(),
            new OrchestrationMetrics(0,0,0,0,0,0), now, now);
        var saved = repository.save(run);
        assertThat(new DurableWorkflowRunRepository(db, mapper).findById(id).orElseThrow().decisions()).isEqualTo(run.decisions());
        repository.save(saved);
        assertThatThrownBy(() -> repository.save(saved)).hasMessageContaining("Stale workflow");
        ownership.lineage(id, id + "-revision", "updated");
        assertThat(new WorkflowOwnershipStore(db, mapper).parent(id + "-revision")).contains(id);
    }
}
