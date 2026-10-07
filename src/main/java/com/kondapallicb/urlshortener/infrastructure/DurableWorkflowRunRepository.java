package com.kondapallicb.urlshortener.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.orchestration.*;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Repository
public class DurableWorkflowRunRepository implements WorkflowRunRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    public DurableWorkflowRunRepository(DataSource source, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(source);
        this.mapper = mapper;
        WorkflowOwnershipStore.schema(jdbc);
    }
    public DurableWorkflowRunRepository(String directory, ObjectMapper mapper) { this(WorkflowOwnershipStore.local(directory), mapper); }
    @Override public WorkflowRun save(WorkflowRun run) {
        if (!run.runId().matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid run ID");
        var next = run.withVersion(run.version() + 1);
        try {
            String payload = mapper.writeValueAsString(next);
            if (run.version() == 0) jdbc.update("INSERT INTO workflow_runs(run_id,version,payload) VALUES (?,?,?)", run.runId(), 1, payload);
            else if (jdbc.update("UPDATE workflow_runs SET version=?,payload=? WHERE run_id=? AND version=?",
                    next.version(), payload, run.runId(), run.version()) != 1)
                throw new IllegalStateException("Stale workflow state transition");
            return next;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    @Override public Optional<WorkflowRun> findById(String id) {
        return jdbc.queryForList("SELECT payload FROM workflow_runs WHERE run_id=?", String.class, id).stream().findFirst().map(this::read);
    }
    @Override public java.util.List<WorkflowRun> all() {
        return jdbc.queryForList("SELECT payload FROM workflow_runs ORDER BY run_id", String.class).stream().map(this::read).toList();
    }
    private WorkflowRun read(String payload) {
        try { return mapper.readValue(payload, WorkflowRun.class); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid durable workflow", invalid); }
    }
}
