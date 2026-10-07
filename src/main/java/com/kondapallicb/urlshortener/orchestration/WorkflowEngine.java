package com.kondapallicb.urlshortener.orchestration;

public interface WorkflowEngine {

    WorkflowRun start(WorkflowScenario scenario, String requirement);

    WorkflowRun get(String runId);

    WorkflowRun approve(String runId, String approver, String comment);
}
