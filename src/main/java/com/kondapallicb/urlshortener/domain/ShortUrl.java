package com.kondapallicb.urlshortener.domain;

import java.net.URI;
import java.time.Instant;

public record ShortUrl(
        String slug,
        URI longUrl,
        Instant createdAt,
        Instant expiresAt,
        boolean active
) {
    public ShortUrl(String slug, URI longUrl, Instant createdAt, Instant expiresAt) {
        this(slug, longUrl, createdAt, expiresAt, true);
    }
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
