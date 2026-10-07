package com.kondapallicb.urlshortener.api;

import com.kondapallicb.urlshortener.orchestration.ScenarioCatalog;
import com.kondapallicb.urlshortener.orchestration.ScenarioDemonstration;
import com.kondapallicb.urlshortener.orchestration.WorkflowEngine;
import com.kondapallicb.urlshortener.orchestration.WorkflowRun;
import com.kondapallicb.urlshortener.orchestration.WorkspaceExecutionService;
import com.kondapallicb.urlshortener.orchestration.ExecutionEvidence;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowEngine workflowEngine;
    private final ScenarioCatalog scenarioCatalog;
    private final WorkspaceExecutionService execution;

    public WorkflowController(WorkflowEngine workflowEngine, ScenarioCatalog scenarioCatalog, WorkspaceExecutionService execution) {
        this.workflowEngine = workflowEngine;
        this.scenarioCatalog = scenarioCatalog;
        this.execution = execution;
    }

    @GetMapping("/{runId}/evidence")
    public ExecutionEvidence evidence(@PathVariable String runId) {
        return execution.evidence(runId);
    }

    @GetMapping("/{runId}/approval-evidence")
    public java.util.Map<String, String> approvalEvidence(@PathVariable String runId) {
        return java.util.Map.of("evidenceHash", execution.approvalHash(workflowEngine.get(runId)));
    }

    public record ClarificationRequest(@jakarta.validation.constraints.NotBlank String requirement) { }

    @PostMapping("/{runId}/clarifications")
    public WorkflowRun clarify(@PathVariable String runId, @Valid @RequestBody ClarificationRequest request) {
        return workflowEngine.clarify(runId, request.requirement());
    }

    @GetMapping("/scenarios")
    public List<ScenarioDemonstration> scenarios() {
        return scenarioCatalog.all();
    }

    @PostMapping
    public WorkflowRun start(@Valid @RequestBody StartWorkflowRequest request) {
        return workflowEngine.start(request.scenario(), request.requirement());
    }

    @GetMapping("/{runId}")
    public WorkflowRun get(@PathVariable String runId) {
        return workflowEngine.get(runId);
    }

    @PostMapping("/{runId}/approvals")
    public WorkflowRun approve(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request,
            java.security.Principal principal) {
        if (principal == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        return workflowEngine.approve(runId, principal.getName(), request.comment(), request.evidenceHash());
    }
}
