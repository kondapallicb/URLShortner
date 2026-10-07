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
    private final WorkspaceExecutionService execution;

    public DefaultWorkflowEngine(WorkflowRunRepository repository, ScenarioCatalog scenarioCatalog, Clock clock,
            WorkspaceExecutionService execution) {
        this.repository = repository;
        this.scenarioCatalog = scenarioCatalog;
        this.clock = clock;
        this.execution = execution;
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
        throw new IllegalStateException("Approval requires authenticated identity and an evidence hash");
    }

    @Override
    public synchronized WorkflowRun approve(String runId, String approver, String comment, String evidenceHash) {
        WorkflowRun run = get(runId);
        if (run.state() != ExecutionState.WAITING_FOR_APPROVAL || run.pendingApprovals().isEmpty()) {
            throw new IllegalStateException("Run has no approvable evidence");
        }
        if (approver == null || approver.isBlank() || evidenceHash == null
                || !evidenceHash.equals(execution.approvalHash(run))) {
            throw new IllegalStateException("Approval does not match current evidence");
        }
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
            decisions.add(new DecisionRecord(now, stage, "APPROVED:" + approval.name(),
                    "Operator=" + approver + "; evidence=" + evidenceHash + "; comment=" + approval.comment()));
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
        repository.save(approved);
        return repository.save(advance(approved));
    }

    @Override
    public synchronized WorkflowRun clarify(String runId, String requirement) {
        WorkflowRun previous = get(runId);
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("Revised requirement is required");
        List<DecisionRecord> decisions = new ArrayList<>(previous.decisions());
        decisions.add(new DecisionRecord(Instant.now(clock), WorkflowStage.REQUIREMENTS,
                "SUPERSEDED", "Artifacts and approvals invalidated by revised requirement: " + requirement));
        repository.save(new WorkflowRun(previous.runId(), previous.scenario(), previous.requirement(), previous.demonstration(),
                ExecutionState.SAFE_STOPPED, previous.graph(), previous.policy(), previous.nodeRuns(), decisions, List.of(),
                previous.metrics(), previous.startedAt(), Instant.now(clock)));
        return start(WorkflowScenario.GREENFIELD, requirement);
    }

    private WorkflowRun advance(WorkflowRun run) {
        WorkflowRun current = run;
        boolean progressed;
        do {
            progressed = false;
            for (WorkflowNode node : current.graph().nodes()) {
                WorkflowNodeRun nodeRun = current.nodeRuns().get(node.stage());
                if (nodeRun.state() == ExecutionState.PENDING && dependenciesCompleted(current, node)) {
                    current = repository.save(executeNode(current, node));
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
        List<String> outputs;
        boolean stopped = false;
        int retries = run.metrics().retryCount();
        int rollbacks = run.metrics().rollbackCount();
        try {
            outputs = outputsFor(run, node);
            stopped = node.stage() == WorkflowStage.REQUIREMENTS
                    && (run.scenario() == WorkflowScenario.AMBIGUOUS || !execution.supports(run.requirement()));
            if (node.stage() == WorkflowStage.TESTING) {
                ExecutionEvidence evidence = execution.evidence(run.runId());
                stopped = !evidence.readiness().equals("VALIDATED_NOT_RELEASE_APPROVED");
                retries = Math.max(0, evidence.attempts().size() - 1);
                rollbacks = evidence.rolledBack() ? 1 : 0;
            }
        } catch (RuntimeException failure) {
            outputs = List.of("Execution stopped: " + failure.getMessage());
            stopped = true;
        }
        boolean approvalRequired = node.approvalGate() != null
                && node.approvalGate().required()
                && run.pendingApprovals().isEmpty()
                && run.decisions().stream().noneMatch(decision -> decision.decision().contains(node.approvalGate().name()));
        WorkflowNodeRun completed = stopped
                ? new WorkflowNodeRun(node.stage(), ExecutionState.SAFE_STOPPED, running.attempts(),
                        running.startedAt(), now, outputs, String.join("; ", outputs))
                : approvalRequired
                ? running.waitingForApproval(now, outputs)
                : running.completed(now, outputs);
        nodeRuns.put(node.stage(), completed);

        List<DecisionRecord> decisions = new ArrayList<>(run.decisions());
        decisions.add(new DecisionRecord(now, node.stage(), stopped ? "SAFE_STOPPED"
                : approvalRequired ? "WAITING_FOR_APPROVAL" : "COMPLETED", String.join("; ", outputs)));

        List<ApprovalGate> pendingApprovals = stopped ? List.of()
                : approvalRequired ? List.of(node.approvalGate()) : run.pendingApprovals();
        ExecutionState state = stopped ? ExecutionState.SAFE_STOPPED
                : approvalRequired ? ExecutionState.WAITING_FOR_APPROVAL : ExecutionState.RUNNING;
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
                metrics(run.startedAt(), nodeRuns, retries, rollbacks),
                run.startedAt(),
                now
        );
    }

    private boolean dependenciesCompleted(WorkflowRun run, WorkflowNode node) {
        return node.dependsOn().stream()
                .allMatch(stage -> run.nodeRuns().get(stage).state() == ExecutionState.COMPLETED);
    }

    private WorkflowRun finalizeIfComplete(WorkflowRun run) {
        boolean allDone = run.nodeRuns().values().stream()
                .allMatch(nodeRun -> nodeRun.state() == ExecutionState.COMPLETED);
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
            case DECOMPOSITION, ARCHITECTURE_DESIGN -> execution.plan(run.requirement());
            case IMPLEMENTATION -> {
                if (!run.nodeRuns().get(WorkflowStage.ARCHITECTURE_DESIGN).outputs().equals(execution.plan(run.requirement()))) {
                    throw new IllegalStateException("Approved source plan changed; clarify and reapprove");
                }
                ExecutionEvidence evidence = execution.execute(run.runId(), run.requirement());
                yield List.of("Applied files: " + evidence.changedFiles(), "Outcome SHA-256: " + evidence.outcomeHash(),
                        "Evidence: " + evidence.workspace());
            }
            case TESTING -> {
                ExecutionEvidence evidence = execution.evidence(run.runId());
                yield List.of("Validation: " + evidence.readiness(), "Executed Maven attempts: " + evidence.attempts());
            }
            case DOCUMENTATION -> List.of("Durable operations.json and evidence.json record the change and validation");
            case RELEASE_READINESS -> List.of("Validated outcome awaits authenticated approval of current evidence hash");
        };
    }

    private List<String> requirementOutputs(WorkflowRun run) {
        List<String> outputs = new ArrayList<>();
        outputs.add("Scenario selected: " + run.demonstration().title());
        outputs.add("Requirement normalized: " + run.requirement());
        outputs.addAll(run.demonstration().ambiguityNotes());
        if (run.scenario() == WorkflowScenario.AMBIGUOUS || !execution.supports(run.requirement())) {
            outputs.addAll(execution.plan(run.requirement()));
        }
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
                .filter(nodeRun -> nodeRun.state() == ExecutionState.COMPLETED)
                .count();
        int failed = (int) nodeRuns.values().stream()
                .filter(nodeRun -> nodeRun.state() == ExecutionState.FAILED || nodeRun.state() == ExecutionState.SAFE_STOPPED)
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
