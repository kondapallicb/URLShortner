package com.kondapallicb.urlshortener.orchestration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DefaultWorkflowEngine implements WorkflowEngine {

    private final WorkflowRunRepository repository;
    private final ScenarioCatalog scenarioCatalog;
    private final Clock clock;

    public DefaultWorkflowEngine(WorkflowRunRepository repository, ScenarioCatalog scenarioCatalog, Clock clock) {
        this.repository = repository;
        this.scenarioCatalog = scenarioCatalog;
        this.clock = clock;
    }

    @Override
    public WorkflowRun start(WorkflowScenario scenario, String requirement) {
        Instant now = Instant.now(clock);
        WorkflowGraph graph = WorkflowGraph.defaultGraph();
        ScenarioDemonstration demonstration = scenarioCatalog.findByScenario(scenario)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported scenario: " + scenario));
        Map<WorkflowStage, WorkflowNodeRun> nodeRuns = new EnumMap<>(WorkflowStage.class);
        for (WorkflowNode node : graph.nodes()) {
            nodeRuns.put(node.stage(), WorkflowNodeRun.pending(node.stage()));
        }
        WorkflowRun run = new WorkflowRun(
                UUID.randomUUID().toString(),
                scenario,
                requirement == null || requirement.isBlank() ? demonstration.requirement() : requirement,
                demonstration,
                ExecutionState.RUNNING,
                graph,
                GovernancePolicy.defaultPolicy(),
                nodeRuns,
                List.of(new DecisionRecord(now, WorkflowStage.REQUIREMENTS, "RUN_STARTED", "Workflow started with governed autonomy")),
                List.of(),
                new OrchestrationMetrics(0, 0, 0, 0, 0, 0),
                now,
                now
        );
        return repository.save(advance(run));
    }

    @Override
    public WorkflowRun get(String runId) {
        return repository.findById(runId).orElseThrow(() -> new WorkflowRunNotFoundException(runId));
    }

    @Override
    public WorkflowRun approve(String runId, String approver, String comment) {
        WorkflowRun run = get(runId);
        List<ApprovalGate> approvals = run.pendingApprovals().stream()
                .map(gate -> gate.approve(approver, comment))
                .toList();
        List<DecisionRecord> decisions = new ArrayList<>(run.decisions());
        Instant now = Instant.now(clock);
        Map<WorkflowStage, WorkflowNodeRun> nodeRuns = new EnumMap<>(run.nodeRuns());
        for (ApprovalGate approval : approvals) {
            WorkflowStage stage = stageForGate(run, approval.name());
            WorkflowNodeRun waitingNode = nodeRuns.get(stage);
            nodeRuns.put(stage, waitingNode.completed(now, waitingNode.outputs()));
            decisions.add(new DecisionRecord(now, stage, "APPROVED:" + approval.name(), approval.comment()));
        }
        WorkflowRun approved = new WorkflowRun(
                run.runId(),
                run.scenario(),
                run.requirement(),
                run.demonstration(),
                ExecutionState.RUNNING,
                run.graph(),
                run.policy(),
                nodeRuns,
                decisions,
                List.of(),
                metrics(run.startedAt(), nodeRuns, run.metrics().retryCount(), run.metrics().rollbackCount()),
                run.startedAt(),
                now
        );
        return repository.save(advance(approved));
    }

    private WorkflowRun advance(WorkflowRun run) {
        WorkflowRun current = run;
        boolean progressed;
        do {
            progressed = false;
            for (WorkflowNode node : current.graph().nodes()) {
                WorkflowNodeRun nodeRun = current.nodeRuns().get(node.stage());
                if (nodeRun.state() == ExecutionState.PENDING && dependenciesCompleted(current, node)) {
                    current = executeNode(current, node);
                    progressed = true;
                    if (current.state() == ExecutionState.WAITING_FOR_APPROVAL || current.state() == ExecutionState.SAFE_STOPPED) {
                        return current;
                    }
                }
            }
        } while (progressed);
        return finalizeIfComplete(current);
    }

    private WorkflowRun executeNode(WorkflowRun run, WorkflowNode node) {
        Instant now = Instant.now(clock);
        Map<WorkflowStage, WorkflowNodeRun> nodeRuns = new EnumMap<>(run.nodeRuns());
        WorkflowNodeRun running = nodeRuns.get(node.stage()).running(now);
        List<String> outputs = outputsFor(run, node);
        boolean approvalRequired = node.approvalGate() != null
                && node.approvalGate().required()
                && run.pendingApprovals().isEmpty()
                && run.decisions().stream().noneMatch(decision -> decision.decision().contains(node.approvalGate().name()));
        WorkflowNodeRun completed = approvalRequired
                ? running.waitingForApproval(now, outputs)
                : running.completed(now, outputs);
        nodeRuns.put(node.stage(), completed);

        List<DecisionRecord> decisions = new ArrayList<>(run.decisions());
        decisions.add(new DecisionRecord(now, node.stage(), approvalRequired ? "WAITING_FOR_APPROVAL" : "COMPLETED", String.join("; ", outputs)));

        List<ApprovalGate> pendingApprovals = approvalRequired ? List.of(node.approvalGate()) : run.pendingApprovals();
        ExecutionState state = approvalRequired ? ExecutionState.WAITING_FOR_APPROVAL : ExecutionState.RUNNING;
        return new WorkflowRun(
                run.runId(),
                run.scenario(),
                run.requirement(),
                run.demonstration(),
                state,
                run.graph(),
                run.policy(),
                nodeRuns,
                decisions,
                pendingApprovals,
                metrics(run.startedAt(), nodeRuns, run.metrics().retryCount(), run.metrics().rollbackCount()),
                run.startedAt(),
                now
        );
    }

    private boolean dependenciesCompleted(WorkflowRun run, WorkflowNode node) {
        return node.dependsOn().stream()
                .allMatch(stage -> run.nodeRuns().get(stage).state() == ExecutionState.COMPLETED
                        || run.nodeRuns().get(stage).state() == ExecutionState.WAITING_FOR_APPROVAL);
    }

    private WorkflowRun finalizeIfComplete(WorkflowRun run) {
        boolean allDone = run.nodeRuns().values().stream()
                .allMatch(nodeRun -> nodeRun.state() == ExecutionState.COMPLETED || nodeRun.state() == ExecutionState.WAITING_FOR_APPROVAL);
        if (!allDone || !run.pendingApprovals().isEmpty()) {
            return run;
        }
        return new WorkflowRun(
                run.runId(),
                run.scenario(),
                run.requirement(),
                run.demonstration(),
                ExecutionState.COMPLETED,
                run.graph(),
                run.policy(),
                run.nodeRuns(),
                run.decisions(),
                run.pendingApprovals(),
                metrics(run.startedAt(), run.nodeRuns(), run.metrics().retryCount(), run.metrics().rollbackCount()),
                run.startedAt(),
                Instant.now(clock)
        );
    }

    private List<String> outputsFor(WorkflowRun run, WorkflowNode node) {
        return switch (node.stage()) {
            case REQUIREMENTS -> requirementOutputs(run);
            case DECOMPOSITION -> run.demonstration().decomposition();
            case ARCHITECTURE_DESIGN -> List.of("Workflow graph selected", "Security and change-control gates attached");
            case IMPLEMENTATION -> List.of("Code artifacts generated under bounded autonomy", "Rollback point recorded");
            case TESTING -> run.demonstration().validationPlan();
            case DOCUMENTATION -> List.of("Architecture and runbook notes generated", "Trade-offs documented");
            case RELEASE_READINESS -> run.demonstration().expectedArtifacts();
        };
    }

    private List<String> requirementOutputs(WorkflowRun run) {
        List<String> outputs = new ArrayList<>();
        outputs.add("Scenario selected: " + run.demonstration().title());
        outputs.add("Requirement normalized: " + run.requirement());
        outputs.addAll(run.demonstration().ambiguityNotes());
        return outputs;
    }

    private WorkflowStage stageForGate(WorkflowRun run, String gateName) {
        return run.graph().nodes().stream()
                .filter(node -> node.approvalGate() != null && node.approvalGate().name().equals(gateName))
                .map(WorkflowNode::stage)
                .findFirst()
                .orElse(WorkflowStage.RELEASE_READINESS);
    }

    private OrchestrationMetrics metrics(
            Instant startedAt,
            Map<WorkflowStage, WorkflowNodeRun> nodeRuns,
            int retryCount,
            int rollbackCount
    ) {
        int completed = (int) nodeRuns.values().stream()
                .filter(nodeRun -> nodeRun.state() == ExecutionState.COMPLETED
                        || nodeRun.state() == ExecutionState.WAITING_FOR_APPROVAL)
                .count();
        int failed = (int) nodeRuns.values().stream()
                .filter(nodeRun -> nodeRun.state() == ExecutionState.FAILED)
                .count();
        double successRate = nodeRuns.isEmpty() ? 0 : (double) completed / nodeRuns.size();
        return new OrchestrationMetrics(
                completed,
                failed,
                retryCount,
                rollbackCount,
                Duration.between(startedAt, Instant.now(clock)).toMillis(),
                successRate
        );
    }
}
