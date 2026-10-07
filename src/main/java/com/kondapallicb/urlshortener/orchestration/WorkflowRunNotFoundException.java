package com.kondapallicb.urlshortener.orchestration;

public class WorkflowRunNotFoundException extends RuntimeException {

    public WorkflowRunNotFoundException(String runId) {
        super("Workflow run was not found: " + runId);
    }
}
