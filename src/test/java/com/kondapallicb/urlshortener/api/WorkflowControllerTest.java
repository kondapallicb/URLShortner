package com.kondapallicb.urlshortener.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kondapallicb.urlshortener.orchestration.ExecutionState;
import com.kondapallicb.urlshortener.orchestration.GovernancePolicy;
import com.kondapallicb.urlshortener.orchestration.OrchestrationMetrics;
import com.kondapallicb.urlshortener.orchestration.DefaultScenarioCatalog;
import com.kondapallicb.urlshortener.orchestration.ScenarioCatalog;
import com.kondapallicb.urlshortener.orchestration.WorkflowEngine;
import com.kondapallicb.urlshortener.orchestration.WorkflowGraph;
import com.kondapallicb.urlshortener.orchestration.WorkflowRun;
import com.kondapallicb.urlshortener.orchestration.WorkflowScenario;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {WorkflowController.class, GlobalExceptionHandler.class})
@org.springframework.context.annotation.Import(com.kondapallicb.urlshortener.application.TimeConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowEngine workflowEngine;

    @MockBean
    private ScenarioCatalog scenarioCatalog;

    @MockBean
    private com.kondapallicb.urlshortener.orchestration.WorkspaceExecutionService execution;

    @Test
    void startsWorkflow() throws Exception {
        when(workflowEngine.start(eq(WorkflowScenario.GREENFIELD), any())).thenReturn(sampleRun());

        mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scenario": "GREENFIELD",
                                  "requirement": "Add custom aliases"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", equalTo("run-1")))
                .andExpect(jsonPath("$.state", equalTo("WAITING_FOR_APPROVAL")));
    }

    @Test
    void approvesWorkflowGate() throws Exception {
        when(workflowEngine.approve(eq("run-1"), eq("lead"), eq("approved"), eq("hash"))).thenReturn(sampleRun());

        mockMvc.perform(post("/api/workflows/run-1/approvals")
                        .principal(() -> "lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "evidenceHash": "hash",
                                  "comment": "approved"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", equalTo("run-1")));
    }

    @Test
    void retrievesWorkflow() throws Exception {
        when(workflowEngine.get("run-1")).thenReturn(sampleRun());

        mockMvc.perform(get("/api/workflows/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.nodes", notNullValue()));
    }

    @Test
    void listsScenarioDemonstrations() throws Exception {
        when(scenarioCatalog.all()).thenReturn(new DefaultScenarioCatalog().all());

        mockMvc.perform(get("/api/workflows/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scenario", equalTo("GREENFIELD")))
                .andExpect(jsonPath("$[1].scenario", equalTo("BROWNFIELD")))
                .andExpect(jsonPath("$[2].scenario", equalTo("AMBIGUOUS")));
    }

    private WorkflowRun sampleRun() {
        Instant now = Instant.parse("2026-10-06T18:00:00Z");
        var demonstration = new DefaultScenarioCatalog().findByScenario(WorkflowScenario.GREENFIELD).orElseThrow();
        return new WorkflowRun(
                "run-1",
                WorkflowScenario.GREENFIELD,
                "Add custom aliases",
                demonstration,
                ExecutionState.WAITING_FOR_APPROVAL,
                WorkflowGraph.defaultGraph(),
                GovernancePolicy.defaultPolicy(),
                Map.of(),
                List.of(),
                List.of(),
                new OrchestrationMetrics(2, 0, 0, 0, 10, 0.28),
                now,
                now
        );
    }
}
