package com.kondapallicb.urlshortener.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kondapallicb.urlshortener.infrastructure.InMemoryWorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DefaultWorkflowEngineTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T18:00:00Z"), ZoneOffset.UTC);
    private final DefaultWorkflowEngine engine = new DefaultWorkflowEngine(new InMemoryWorkflowRunRepository(), clock);

    @Test
    void startsAndStopsAtArchitectureApprovalGate() {
        WorkflowRun run = engine.start(WorkflowScenario.GREENFIELD, "Build URL shortener");

        assertThat(run.state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
        assertThat(run.pendingApprovals()).hasSize(1);
        assertThat(run.pendingApprovals().getFirst().name()).isEqualTo("architecture-review");
        assertThat(run.nodeRuns().get(WorkflowStage.REQUIREMENTS).state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(run.nodeRuns().get(WorkflowStage.DECOMPOSITION).state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(run.nodeRuns().get(WorkflowStage.ARCHITECTURE_DESIGN).state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
    }

    @Test
    void approvalAdvancesWorkflowToReleaseGateThenCompletion() {
        WorkflowRun run = engine.start(WorkflowScenario.BROWNFIELD, "Add analytics endpoint");

        WorkflowRun releaseGate = engine.approve(run.runId(), "tech-lead", "Architecture approved");

        assertThat(releaseGate.state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
        assertThat(releaseGate.pendingApprovals()).hasSize(1);
        assertThat(releaseGate.pendingApprovals().getFirst().name()).isEqualTo("release-readiness");
        assertThat(releaseGate.nodeRuns().get(WorkflowStage.TESTING).state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(releaseGate.nodeRuns().get(WorkflowStage.DOCUMENTATION).state()).isEqualTo(ExecutionState.COMPLETED);

        WorkflowRun completed = engine.approve(releaseGate.runId(), "owner", "Release approved");

        assertThat(completed.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(completed.pendingApprovals()).isEmpty();
        assertThat(completed.metrics().successRate()).isEqualTo(1.0);
    }
}
