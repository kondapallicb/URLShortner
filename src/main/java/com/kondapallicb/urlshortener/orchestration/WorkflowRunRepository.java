package com.kondapallicb.urlshortener.orchestration;

import java.util.Optional;

public interface WorkflowRunRepository {

    java.util.List<WorkflowRun> all();

    WorkflowRun save(WorkflowRun run);

    Optional<WorkflowRun> findById(String runId);
}
