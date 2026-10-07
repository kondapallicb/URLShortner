package com.kondapallicb.urlshortener.infrastructure;

import com.kondapallicb.urlshortener.domain.ShortUrl;
import com.kondapallicb.urlshortener.domain.UrlMappingRepository;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryUrlMappingRepository implements UrlMappingRepository {

    private final ConcurrentMap<String, ShortUrl> mappings = new ConcurrentHashMap<>();

    @Override
    public ShortUrl save(ShortUrl shortUrl) {
        mappings.put(shortUrl.slug(), shortUrl);
        return shortUrl;
    }

    @Override
    public Optional<ShortUrl> findBySlug(String slug) {
        return Optional.ofNullable(mappings.get(slug));
    }
}
