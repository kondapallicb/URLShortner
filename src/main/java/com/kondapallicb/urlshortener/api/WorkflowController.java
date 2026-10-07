package com.kondapallicb.urlshortener.api;

import com.kondapallicb.urlshortener.orchestration.WorkflowEngine;
import com.kondapallicb.urlshortener.orchestration.WorkflowRun;
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

    public WorkflowController(WorkflowEngine workflowEngine) {
        this.workflowEngine = workflowEngine;
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
    public WorkflowRun approve(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request) {
        return workflowEngine.approve(runId, request.approver(), request.comment());
    }
}
