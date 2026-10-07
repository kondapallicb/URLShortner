package com.kondapallicb.urlshortener.api;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kondapallicb.urlshortener.observability.ArchitectureOverview;
import com.kondapallicb.urlshortener.observability.EngineeringSummary;
import com.kondapallicb.urlshortener.observability.EngineeringSummaryService;
import com.kondapallicb.urlshortener.observability.ReleaseReadiness;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemStatusController.class)
@org.springframework.context.annotation.Import(com.kondapallicb.urlshortener.application.TimeConfiguration.class)
class SystemStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EngineeringSummaryService engineeringSummaryService;

    @Test
    void returnsSystemStatus() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("UP")))
                .andExpect(jsonPath("$.service", equalTo("url-shortener-agentic")));
    }

    @Test
    void returnsEngineeringSummary() throws Exception {
        when(engineeringSummaryService.summary()).thenReturn(new EngineeringSummary(
                "Build a URL shortener",
                new ArchitectureOverview("Layered", List.of("api"), List.of("request -> service"), List.of("approval gates")),
                new ReleaseReadiness("POC_READY_FOR_REVIEW", List.of("done"), List.of("mvn test"), List.of("rollback")),
                List.of("controller tests"),
                List.of("in-memory"),
                List.of("simple POC"),
                List.of("no durable database"),
                List.of("add persistence")
        ));

        mockMvc.perform(get("/api/system/engineering-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseReadiness.status", equalTo("POC_READY_FOR_REVIEW")))
                .andExpect(jsonPath("$.architecture.style", equalTo("Layered")));
    }
}
