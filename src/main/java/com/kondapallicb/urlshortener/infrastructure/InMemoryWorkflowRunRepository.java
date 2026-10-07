package com.kondapallicb.urlshortener.infrastructure;

import com.kondapallicb.urlshortener.orchestration.WorkflowRun;
import com.kondapallicb.urlshortener.orchestration.WorkflowRunRepository;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryWorkflowRunRepository implements WorkflowRunRepository {

    private final ConcurrentMap<String, WorkflowRun> runs = new ConcurrentHashMap<>();

    @Override
    public WorkflowRun save(WorkflowRun run) {
        runs.put(run.runId(), run);
        return run;
    }

    @Override
    public Optional<WorkflowRun> findById(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }
}
