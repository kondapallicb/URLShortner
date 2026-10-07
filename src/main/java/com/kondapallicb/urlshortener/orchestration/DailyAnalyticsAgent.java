package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public class DailyAnalyticsAgent {
    public List<FileOperation> implement() {
        return List.of(new FileOperation("src/main/java/com/kondapallicb/urlshortener/api/DailyAnalyticsController.java", """
                package com.kondapallicb.urlshortener.api;
                import com.kondapallicb.urlshortener.application.UrlShorteningService;
                import java.time.LocalDate;
                import java.util.Map;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/analytics")
                public class DailyAnalyticsController {
                    private final UrlShorteningService service;
                    public DailyAnalyticsController(UrlShorteningService service) { this.service = service; }
                    @GetMapping("/{slug}/daily")
                    public Map<LocalDate, Long> daily(@PathVariable String slug) {
                        return service.analytics(slug).dailyClicksUtc();
                    }
                }
                """));
    }
    public List<FileOperation> tests() {
        return List.of(new FileOperation("src/test/java/com/kondapallicb/urlshortener/api/DailyAnalyticsAcceptanceTest.java", """
                package com.kondapallicb.urlshortener.api;
                import com.kondapallicb.urlshortener.domain.*;
                import java.net.URI;
                import java.time.Instant;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.MockMvc;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
                @SpringBootTest @AutoConfigureMockMvc
                class DailyAnalyticsAcceptanceTest {
                    @Autowired MockMvc mvc;
                    @Autowired UrlMappingRepository repository;
                    @Test void groupsByUtcDate() throws Exception {
                        Instant now = Instant.now();
                        repository.save(new ShortUrl("daily-report-test", URI.create("https://example.org"), now, now.plusSeconds(60)));
                        repository.recordClick(new UrlClickEvent("daily-report-test", Instant.parse("2026-01-01T23:59:59Z"), null, null, null));
                        repository.recordClick(new UrlClickEvent("daily-report-test", Instant.parse("2026-01-02T00:00:00Z"), null, null, null));
                        mvc.perform(get("/api/analytics/daily-report-test/daily")).andExpect(status().isOk())
                            .andExpect(jsonPath("$['2026-01-01']").value(1)).andExpect(jsonPath("$['2026-01-02']").value(1));
                    }
                    @Test void unknownSlugReturns404() throws Exception {
                        mvc.perform(get("/api/analytics/unknown-analytics-slug/daily")).andExpect(status().isNotFound());
                    }
                }
                """));
    }
}
