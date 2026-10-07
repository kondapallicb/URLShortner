package com.kondapallicb.urlshortener.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.kondapallicb.urlshortener.infrastructure.InMemoryWorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DefaultWorkflowEngineTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T18:00:00Z"), ZoneOffset.UTC);
    private final WorkspaceExecutionService execution = mock(WorkspaceExecutionService.class);
    private final DefaultWorkflowEngine engine = new DefaultWorkflowEngine(
            new InMemoryWorkflowRunRepository(),
            new DefaultScenarioCatalog(),
            clock,
            execution
    );

    @Test
    void startsAndStopsAtArchitectureApprovalGate() {
        when(execution.isReady(anyString())).thenReturn(true);
        when(execution.plan(anyString())).thenReturn(java.util.List.of("Analyzed plan"));
        when(execution.graph(anyString(), any())).thenReturn(WorkflowGraph.defaultGraph());
        WorkflowRun run = engine.start(WorkflowScenario.GREENFIELD, "Build URL shortener");

        assertThat(run.state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
        assertThat(run.demonstration().title()).isEqualTo("Greenfield: Custom Alias Support");
        assertThat(run.pendingApprovals()).hasSize(1);
        assertThat(run.pendingApprovals().getFirst().name()).isEqualTo("architecture-review");
        assertThat(run.nodeRuns().get(WorkflowStage.REQUIREMENTS).state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(run.nodeRuns().get(WorkflowStage.DECOMPOSITION).state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(run.nodeRuns().get(WorkflowStage.ARCHITECTURE_DESIGN).state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
    }

    @Test
    void approvalAdvancesWorkflowToReleaseGateThenCompletion() {
        when(execution.isReady(anyString())).thenReturn(true);
        when(execution.plan(anyString())).thenReturn(java.util.List.of("Analyzed plan"));
        when(execution.graph(anyString(), any())).thenReturn(WorkflowGraph.defaultGraph());
        var evidence = new ExecutionEvidence("run", "requirement", "baseline", "outcome", "workspace",
                java.util.List.of("source"), java.util.List.of(), false, "VALIDATED_NOT_RELEASE_APPROVED", "artifact.jar", "hash");
        when(execution.execute(anyString(), anyString(), any())).thenReturn(evidence);
        when(execution.evidence(anyString())).thenReturn(evidence);
        WorkflowRun run = engine.start(WorkflowScenario.BROWNFIELD, "Add analytics endpoint");

        when(execution.approvalHash(any())).thenReturn("hash");
        WorkflowRun releaseGate = engine.approve(run.runId(), "tech-lead", "Architecture approved", "hash");

        assertThat(releaseGate.state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
        assertThat(releaseGate.pendingApprovals()).hasSize(1);
        assertThat(releaseGate.pendingApprovals().getFirst().name()).isEqualTo("release-readiness");
        assertThat(releaseGate.nodeRuns().get(WorkflowStage.TESTING).state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(releaseGate.nodeRuns().get(WorkflowStage.DOCUMENTATION).state()).isEqualTo(ExecutionState.COMPLETED);

        assertThatThrownBy(() -> engine.approve(releaseGate.runId(), "owner", "Release approved"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("authenticated");
    }

    @Test
    void unresolvedRequirementStopsWithActualInterpretationNotScenarioNotes() {
        String requirement = "Add custom aliases and support an undefined proprietary ownership protocol.";
        when(execution.plan(requirement)).thenReturn(java.util.List.of("Unsupported ownership protocol; specify its verification contract"));
        WorkflowRun run = engine.start(WorkflowScenario.BROWNFIELD, requirement);

        assertThat(run.nodeRuns().get(WorkflowStage.REQUIREMENTS).outputs())
                .contains("Unsupported ownership protocol; specify its verification contract")
                .doesNotContain("Brand ownership verification is undefined");
        assertThat(run.state()).isEqualTo(ExecutionState.SAFE_STOPPED);
        assertThat(run.pendingApprovals()).isEmpty();
        verify(execution, never()).execute(anyString(), anyString(), any());
    }

    @Test
    void scenarioLabelDoesNotOverrideReadyRequirements() {
        when(execution.isReady(anyString())).thenReturn(true);
        when(execution.plan(anyString())).thenReturn(java.util.List.of("Analyzed plan"));
        when(execution.graph(anyString(), any())).thenReturn(WorkflowGraph.defaultGraph());
        var run = engine.start(WorkflowScenario.AMBIGUOUS, "Add custom aliases");
        assertThat(run.state()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);
        assertThat(run.pendingApprovals().getFirst().name()).isEqualTo("architecture-review");
    }
}
