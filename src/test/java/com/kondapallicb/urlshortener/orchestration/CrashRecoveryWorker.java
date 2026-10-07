package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.infrastructure.WorkflowOwnershipStore;
import java.nio.file.*;
import java.time.Duration;

public class CrashRecoveryWorker {
    public static void main(String[] args) {
        try { Files.createDirectories(Path.of(args[0])); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        javax.sql.DataSource database = args[2].startsWith("jdbc:postgresql:")
            ? new org.springframework.jdbc.datasource.DriverManagerDataSource(args[2], "workflow_test", "")
            : WorkflowOwnershipStore.local(args[2]);
        var service = new WorkspaceExecutionService(".", args[0], "mvn", new ObjectMapper(), new AliasImplementationAgent(), database) {
            @Override protected Duration ownershipLease() { return Duration.ofMillis(300); }
            @Override protected void applyOperation(Path workspace, FileOperation operation) throws java.io.IOException {
                super.applyOperation(workspace, operation);
                Files.writeString(Path.of(args[0]).resolve("worker-ready"), "partial application");
                try { Thread.sleep(Duration.ofMinutes(10)); }
                catch (InterruptedException interruption) { throw new java.io.IOException(interruption); }
            }
        };
        if (args.length > 3) {
            var mapper = new ObjectMapper().findAndRegisterModules();
            var engine = new DefaultWorkflowEngine(new com.kondapallicb.urlshortener.infrastructure.DurableWorkflowRunRepository(database, mapper),
                new DefaultScenarioCatalog(), java.time.Clock.systemUTC(), service);
            var planned = engine.start(WorkflowScenario.BROWNFIELD, "Add custom aliases");
            try { Files.writeString(Path.of(args[0]).resolve("workflow-id"), planned.runId()); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            engine.approve(planned.runId(), "operator", "Approved exact plan", service.approvalHash(planned));
        } else service.execute(args[1], "Add custom aliases");
    }
}
