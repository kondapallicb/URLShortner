package com.kondapallicb.urlshortener.domain;

import java.time.Instant;

public record UrlAnalytics(
        String slug,
        long totalClicks,
        Instant createdAt,
        Instant expiresAt,
        Instant lastAccessedAt,
        java.util.Map<java.time.LocalDate, Long> dailyClicksUtc
) {
    public UrlAnalytics(String slug, long totalClicks, Instant createdAt, Instant expiresAt, Instant lastAccessedAt) {
        this(slug, totalClicks, createdAt, expiresAt, lastAccessedAt, java.util.Map.of());
    }
}
