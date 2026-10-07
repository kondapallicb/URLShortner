package com.kondapallicb.urlshortener.api;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kondapallicb.urlshortener.application.UrlShorteningService;
import com.kondapallicb.urlshortener.domain.ShortUrl;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {UrlController.class, GlobalExceptionHandler.class})
class UrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlShorteningService urlShorteningService;

    @Test
    void createsShortUrl() throws Exception {
        when(urlShorteningService.create(any())).thenReturn(new ShortUrl(
                "AbC123x",
                URI.create("https://example.com/articles/agentic-engineering"),
                Instant.parse("2026-10-06T18:00:00Z"),
                Instant.parse("2026-11-05T18:00:00Z")
        ));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "longUrl": "https://example.com/articles/agentic-engineering",
                                  "ttlSeconds": 86400
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug", equalTo("AbC123x")))
                .andExpect(jsonPath("$.longUrl", equalTo("https://example.com/articles/agentic-engineering")))
                .andExpect(jsonPath("$.shortUrl", equalTo("http://localhost/AbC123x")));
    }

    @Test
    void rejectsInvalidUrl() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "longUrl": "not-a-url"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    void redirectsToLongUrl() throws Exception {
        when(urlShorteningService.resolve("AbC123x"))
                .thenReturn(URI.create("https://example.com/articles/agentic-engineering"));

        mockMvc.perform(get("/AbC123x"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.com/articles/agentic-engineering"))
                .andExpect(redirectedUrl("https://example.com/articles/agentic-engineering"));
    }
}
