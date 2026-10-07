package com.kondapallicb.urlshortener.api;

import java.time.Instant;

public record CreateShortUrlResponse(
        String slug,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt
) {
}
