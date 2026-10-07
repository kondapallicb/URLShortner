package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Spring facade; ExecutionCoordinator owns proposals, attempts, recovery and finalization.
@Service
public class WorkspaceExecutionService extends ExecutionCoordinator {
    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceExecutionService(@Value("${app.execution.repository:.}") String source,
            @Value("${app.execution.evidence:./workflow-evidence}") String evidence,
            @Value("${app.execution.maven:mvn}") String maven, ObjectMapper mapper, DataSource database) {
        super(source, evidence, maven, mapper, new AliasImplementationAgent(), database);
    }
    public WorkspaceExecutionService(String source, String evidence, String maven, ObjectMapper mapper) {
        super(source, evidence, maven, mapper);
    }
    WorkspaceExecutionService(String source, String evidence, String maven, ObjectMapper mapper, AliasImplementationAgent agent) {
        super(source, evidence, maven, mapper, agent);
    }
    WorkspaceExecutionService(String source, String evidence, String maven, ObjectMapper mapper, AliasImplementationAgent agent, DataSource database) {
        super(source, evidence, maven, mapper, agent, database);
    }
}
