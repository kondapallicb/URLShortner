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
@AutoConfigureMockMvc(addFilters = false)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowEngine workflowEngine;

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
        when(workflowEngine.approve(eq("run-1"), eq("lead"), eq("approved"))).thenReturn(sampleRun());

        mockMvc.perform(post("/api/workflows/run-1/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approver": "lead",
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

    private WorkflowRun sampleRun() {
        Instant now = Instant.parse("2026-10-06T18:00:00Z");
        return new WorkflowRun(
                "run-1",
                WorkflowScenario.GREENFIELD,
                "Add custom aliases",
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
