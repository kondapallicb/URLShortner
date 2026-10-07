package com.kondapallicb.urlshortener.domain;

import java.net.URI;
import java.time.Instant;

public record ShortUrl(
        String slug,
        URI longUrl,
        Instant createdAt,
        Instant expiresAt
) {
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
