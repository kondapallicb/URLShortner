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
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public WorkflowController(WorkflowEngine workflowEngine, ScenarioCatalog scenarioCatalog, WorkspaceExecutionService execution,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.workflowEngine = workflowEngine;
        this.scenarioCatalog = scenarioCatalog;
        this.execution = execution;
        this.mapper = mapper;
    }

    @GetMapping("/{runId}/evidence")
    public ExecutionEvidence evidence(@PathVariable String runId) {
        return execution.evidence(runId);
    }

    @GetMapping("/{runId}/approval-evidence")
    public java.util.Map<String, String> approvalEvidence(@PathVariable String runId) {
        return java.util.Map.of("evidenceHash", execution.approvalHash(workflowEngine.get(runId)));
    }

    @GetMapping("/{runId}/interpretation")
    public com.kondapallicb.urlshortener.orchestration.RequirementInterpretation interpretation(@PathVariable String runId) {
        return execution.interpretation(workflowEngine.get(runId).requirement());
    }
    @GetMapping("/{runId}/lineage")
    public java.util.Map<String, String> lineage(@PathVariable String runId) {
        return java.util.Map.of("revisionId", runId, "parentRevisionId", execution.parentRevision(runId).orElse(""));
    }

    @GetMapping("/{runId}/plan")
    public com.kondapallicb.urlshortener.orchestration.EngineeringPlan plan(@PathVariable String runId) {
        return execution.detailedPlan(workflowEngine.get(runId).requirement());
    }

    @GetMapping("/{runId}/security-evidence")
    public com.kondapallicb.urlshortener.orchestration.PolicyReport securityEvidence(@PathVariable String runId) {
        return execution.policyReport(runId);
    }

    public record ClarificationRequest(String requirement, com.kondapallicb.urlshortener.orchestration.RequirementSpec specification) { }

    @PostMapping("/{runId}/clarifications")
    public WorkflowRun clarify(@PathVariable String runId, @Valid @RequestBody ClarificationRequest request) {
        return workflowEngine.clarify(runId, normalizeRequirement(request.requirement(), request.specification()));
    }

    @GetMapping("/scenarios")
    public List<ScenarioDemonstration> scenarios() {
        return scenarioCatalog.all();
    }

    @PostMapping
    public WorkflowRun start(@Valid @RequestBody StartWorkflowRequest request) {
        return workflowEngine.start(request.scenario(), normalizeRequirement(request.requirement(), request.specification()));
    }

    private String normalizeRequirement(String text, com.kondapallicb.urlshortener.orchestration.RequirementSpec specification) {
        if (specification != null && text != null && !text.isBlank()) {
            throw new IllegalArgumentException("Use a structured specification or requirement text, not both");
        }
        String requirement = text;
        if (specification != null) {
            try { requirement = mapper.writeValueAsString(specification); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid specification", invalid); }
        }
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("Requirement or specification required");
        return requirement;
    }

    @GetMapping("/{runId}")
    public WorkflowRun get(@PathVariable String runId) {
        return workflowEngine.get(runId);
    }

    @PostMapping("/{runId}/approvals")
    public WorkflowRun approve(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request,
            java.security.Principal principal, jakarta.servlet.http.HttpServletRequest servletRequest) {
        if (principal == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        var run = workflowEngine.get(runId);
        boolean security = run.pendingApprovals().stream().anyMatch(gate -> gate.name().equals("security-review"));
        if (!servletRequest.isUserInRole(security ? "SECURITY_REVIEWER" : "OPERATOR")) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return workflowEngine.approve(runId, principal.getName(), request.comment(), request.evidenceHash());
    }
}
