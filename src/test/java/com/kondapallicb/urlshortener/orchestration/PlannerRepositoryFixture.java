package com.kondapallicb.urlshortener.orchestration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PlannerRepositoryFixture {
    private PlannerRepositoryFixture() { }
    static Path create(Path root) throws IOException {
        Path directory = root.resolve("src/main/java/example");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("UrlController.java"), "package example; class UrlController { void redirect() { resolveAndRecordClick(); } void resolveAndRecordClick() {} }");
        Files.writeString(directory.resolve("UrlMappingRepository.java"), "package example; interface UrlMappingRepository { void save(); }");
        Files.writeString(directory.resolve("UrlShorteningService.java"), "package example; interface UrlShorteningService { void analytics(); }");
        Files.writeString(directory.resolve("UrlAnalytics.java"), "package example; record UrlAnalytics(java.util.Map<java.time.LocalDate,Long> dailyClicksUtc) {}");
        return root;
    }
}
