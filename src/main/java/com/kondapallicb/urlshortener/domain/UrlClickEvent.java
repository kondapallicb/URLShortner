package com.kondapallicb.urlshortener.domain;

import java.time.Instant;

public record UrlClickEvent(
        String slug,
        Instant clickedAt,
        String clientIp,
        String userAgent,
        String referer
) {
}
