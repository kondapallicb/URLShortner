package com.kondapallicb.urlshortener.domain;

import java.util.Optional;

public interface UrlMappingRepository {

    ShortUrl save(ShortUrl shortUrl);

    Optional<ShortUrl> findBySlug(String slug);
}
