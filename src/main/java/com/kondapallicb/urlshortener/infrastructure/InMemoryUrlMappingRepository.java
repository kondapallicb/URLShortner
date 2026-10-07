package com.kondapallicb.urlshortener.infrastructure;

import com.kondapallicb.urlshortener.domain.ShortUrl;
import com.kondapallicb.urlshortener.domain.UrlAnalytics;
import com.kondapallicb.urlshortener.domain.UrlClickEvent;
import com.kondapallicb.urlshortener.domain.UrlMappingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryUrlMappingRepository implements UrlMappingRepository {

    private final ConcurrentMap<String, ShortUrl> mappings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<UrlClickEvent>> clicks = new ConcurrentHashMap<>();

    @Override
    public ShortUrl save(ShortUrl shortUrl) {
        mappings.put(shortUrl.slug(), shortUrl);
        return shortUrl;
    }

    @Override
    public Optional<ShortUrl> findBySlug(String slug) {
        return Optional.ofNullable(mappings.get(slug));
    }

    @Override
    public Optional<ShortUrl> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(idempotencyKeys.get(idempotencyKey))
                .flatMap(this::findBySlug);
    }

    @Override
    public void saveIdempotencyKey(String idempotencyKey, String slug) {
        idempotencyKeys.putIfAbsent(idempotencyKey, slug);
    }

    @Override
    public void recordClick(UrlClickEvent event) {
        List<UrlClickEvent> events = clicks.computeIfAbsent(event.slug(), key -> new ArrayList<>());
        synchronized (events) {
            events.add(event);
        }
    }

    @Override
    public UrlAnalytics analyticsFor(String slug) {
        ShortUrl shortUrl = mappings.get(slug);
        List<UrlClickEvent> events = clicks.getOrDefault(slug, List.of());
        Instant lastAccessedAt;
        synchronized (events) {
            lastAccessedAt = events.stream()
                    .map(UrlClickEvent::clickedAt)
                    .max(Instant::compareTo)
                    .orElse(null);
            return new UrlAnalytics(
                    slug,
                    events.size(),
                    shortUrl.createdAt(),
                    shortUrl.expiresAt(),
                    lastAccessedAt
            );
        }
    }
}
