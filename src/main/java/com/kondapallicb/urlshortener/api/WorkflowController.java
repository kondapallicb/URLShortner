package com.kondapallicb.urlshortener.api;

import com.kondapallicb.urlshortener.orchestration.ScenarioCatalog;
import com.kondapallicb.urlshortener.orchestration.ScenarioDemonstration;
import com.kondapallicb.urlshortener.orchestration.WorkflowEngine;
import com.kondapallicb.urlshortener.orchestration.WorkflowRun;
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

    public WorkflowController(WorkflowEngine workflowEngine, ScenarioCatalog scenarioCatalog) {
        this.workflowEngine = workflowEngine;
        this.scenarioCatalog = scenarioCatalog;
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
    public WorkflowRun approve(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request) {
        return workflowEngine.approve(runId, request.approver(), request.comment());
    }
}
