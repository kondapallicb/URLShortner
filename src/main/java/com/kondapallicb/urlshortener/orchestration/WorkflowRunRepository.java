package com.kondapallicb.urlshortener.orchestration;

import java.util.Optional;

public interface WorkflowRunRepository {

    WorkflowRun save(WorkflowRun run);

    Optional<WorkflowRun> findById(String runId);
}
