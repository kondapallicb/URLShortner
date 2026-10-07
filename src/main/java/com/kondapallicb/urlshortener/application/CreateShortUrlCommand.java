package com.kondapallicb.urlshortener.application;

import java.net.URI;

public record CreateShortUrlCommand(URI longUrl, Long ttlSeconds, String idempotencyKey, String customAlias) {
    public CreateShortUrlCommand(URI longUrl, Long ttlSeconds, String idempotencyKey) {
        this(longUrl, ttlSeconds, idempotencyKey, null);
    }
}
