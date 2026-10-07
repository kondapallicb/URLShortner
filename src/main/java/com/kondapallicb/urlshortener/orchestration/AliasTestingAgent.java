package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public class AliasTestingAgent {
    public List<FileOperation> tests() {
        return List.of(new FileOperation(
                "src/test/java/com/kondapallicb/urlshortener/api/CustomAliasAcceptanceTest.java", """
                package com.kondapallicb.urlshortener.api;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.MockMvc;
                import org.springframework.http.MediaType;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
                @SpringBootTest
                @AutoConfigureMockMvc
                class CustomAliasAcceptanceTest {
                    @Autowired MockMvc mvc;
                    @Test void aliasRedirectsAndRejectsReplacement() throws Exception {
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"alias\\":\\"campaign-test\\",\\"longUrl\\":\\"https://example.org/campaign\\"}"))
                            .andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("campaign-test"));
                        mvc.perform(get("/campaign-test")).andExpect(status().isFound())
                            .andExpect(redirectedUrl("https://example.org/campaign"));
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"alias\\":\\"campaign-test\\",\\"longUrl\\":\\"https://example.org/replacement\\"}"))
                            .andExpect(status().isConflict());
                        mvc.perform(get("/campaign-test")).andExpect(redirectedUrl("https://example.org/campaign"));
                    }
                    @Test void invalidAndReservedAliasesAreRejected() throws Exception {
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"alias\\":\\"a b\\",\\"longUrl\\":\\"https://example.org\\"}"))
                            .andExpect(status().isBadRequest());
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"alias\\":\\"api\\",\\"longUrl\\":\\"https://example.org\\"}"))
                            .andExpect(status().isConflict());
                    }
                }
                """));
    }
}
