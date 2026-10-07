package com.kondapallicb.urlshortener.application;

import java.net.URI;

public record CreateShortUrlCommand(URI longUrl, Long ttlSeconds) {
}
