package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public class AliasTestingAgent {
    public List<FileOperation> tests(RequirementSpec.AliasOptions options) {
        String alias = available("c".repeat(options.minLength()), options.minLength(), options.reservedAliases());
        var blocked = new java.util.ArrayList<>(options.reservedAliases()); blocked.add(alias);
        String maximum = available("t".repeat(options.maxLength()), options.maxLength(), blocked);

        String additional = """
                    @Test void aliasBoundariesAndTtl() throws Exception {
                        clock.set(java.time.Instant.parse("2030-01-01T00:00:00Z"));
                        var result = mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"alias\\":\\"%s\\",\\"longUrl\\":\\"https://example.org/ttl\\"}"))
                            .andExpect(status().isCreated()).andReturn();
                        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
                        org.junit.jupiter.api.Assertions.assertEquals(%dL, java.time.Duration.between(
                            java.time.Instant.parse(json.get("createdAt").asText()),
                            java.time.Instant.parse(json.get("expiresAt").asText())).getSeconds());
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"alias\\":\\"%s\\",\\"longUrl\\":\\"https://example.org\\"}"))
                            .andExpect(status().isBadRequest());
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"alias\\":\\"%s\\",\\"longUrl\\":\\"https://example.org\\"}"))
                            .andExpect(status().isBadRequest());
                        clock.set(java.time.Instant.parse("2030-01-01T00:00:00Z").plusSeconds(%d));
                        mvc.perform(get("/%s")).andExpect(status().isFound());
                        clock.set(java.time.Instant.parse("2030-01-01T00:00:00Z").plusSeconds(%d));
                        mvc.perform(get("/%s")).andExpect(status().isGone());
                    }
                """.formatted(maximum, options.ttlSeconds(),
                        "a".repeat(options.minLength() - 1), "a".repeat(options.maxLength() + 1),
                        options.ttlSeconds() - 1, maximum, options.ttlSeconds(), maximum);
        String lower = "z" + "0".repeat(options.minLength() - 2) + "1";
        for (int number = 2; blocked.contains(lower) || blocked.contains(lower.toUpperCase(java.util.Locale.ROOT)); number++)
            lower = "z" + String.format(java.util.Locale.ROOT, "%0" + (options.minLength() - 1) + "d", number);
        String symbol = "0".repeat(options.minLength() - 1) + "_";
        for (int number = 1; blocked.contains(symbol); number++) symbol = String.format(java.util.Locale.ROOT, "%0" + (options.minLength() - 1) + "d", number) + "_";
        String parameterProof = """
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                java.util.Map.of("alias", "%s", "longUrl", "https://example.org/lower"))))
                            .andExpect(status().isCreated());
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                java.util.Map.of("alias", "%s", "longUrl", "https://example.org/upper"))))
                            .andExpect(status().isCreated());
                        mvc.perform(get("/%s")).andExpect(redirectedUrl("https://example.org/lower"));
                        mvc.perform(get("/%s")).andExpect(redirectedUrl("https://example.org/upper"));
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                java.util.Map.of("alias", "%s", "longUrl", "https://example.org/symbol"))))
                            .andExpect(status().is(%d));
                """.formatted(lower, lower.toUpperCase(java.util.Locale.ROOT), lower, lower.toUpperCase(java.util.Locale.ROOT),
                    symbol, options.alphabet() == RequirementSpec.AliasOptions.Alphabet.URL_SAFE ? 201 : 400);
        final String proof = additional.replace("    var result = mvc.perform", parameterProof + "    var result = mvc.perform");
        return tests().stream().map(op -> {
            String content = op.content().replace("campaign-test", alias);
            int reservedStart = content.indexOf("    @Test void invalidAndReservedAliasesAreRejected()");
            int end = content.lastIndexOf('}');
            var checks = new StringBuilder("""
                    @Test void invalidAndReservedAliasesAreRejected() throws Exception {
                        mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                            .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                java.util.Map.of("alias", "a b", "longUrl", "https://example.org"))))
                            .andExpect(status().isBadRequest());
                """);
            for (String name : options.reservedAliases()) {
                int expected = name.length() >= options.minLength() && name.length() <= options.maxLength() ? options.duplicateStatus() : 400;
                checks.append("""
                    mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                            java.util.Map.of("alias", "%s", "longUrl", "https://example.org"))))
                        .andExpect(status().is(%d));
                    """.formatted(name, expected));
            }
            if (options.reservedAliases().isEmpty() && options.minLength() <= 3 && options.maxLength() >= 3) {
                checks.append("""
                    mvc.perform(post("/api/aliases").contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                            java.util.Map.of("alias", "api", "longUrl", "https://example.org"))))
                        .andExpect(status().isCreated());
                    """);
            }
            checks.append("}\n");
            content = content.substring(0, reservedStart) + checks + content.substring(end);
            int closing = content.lastIndexOf('}');
            return new FileOperation(op.path(), content.substring(0, closing) + proof + "}\n");
        }).toList();
    }
    private String available(String candidate, int length, List<String> blocked) {
        for (int number = 0; blocked.contains(candidate); number++) candidate = String.format(java.util.Locale.ROOT, "%0" + length + "d", number);
        return candidate;
    }
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
                    @Autowired MutableClock clock;
                    @org.springframework.boot.test.context.TestConfiguration
                    static class TimeConfig {
                        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
                        MutableClock acceptanceClock() { return new MutableClock(); }
                    }
                    static class MutableClock extends java.time.Clock {
                        private java.time.Instant instant = java.time.Instant.parse("2030-01-01T00:00:00Z");
                        void set(java.time.Instant value) { instant = value; }
                        public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
                        public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
                        public java.time.Instant instant() { return instant; }
                    }
                    @org.junit.jupiter.api.BeforeEach void resetClock() {
                        clock.set(java.time.Instant.parse("2030-01-01T00:00:00Z"));
                    }
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
