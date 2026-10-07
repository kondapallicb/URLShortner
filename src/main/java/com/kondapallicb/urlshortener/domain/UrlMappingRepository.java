package com.kondapallicb.urlshortener.domain;

import java.util.Optional;

public interface UrlMappingRepository {

    ShortUrl save(ShortUrl shortUrl);

    Optional<ShortUrl> findBySlug(String slug);

    Optional<ShortUrl> findByIdempotencyKey(String idempotencyKey);

    void saveIdempotencyKey(String idempotencyKey, String slug);

    void recordClick(UrlClickEvent event);

    UrlAnalytics analyticsFor(String slug);
}
