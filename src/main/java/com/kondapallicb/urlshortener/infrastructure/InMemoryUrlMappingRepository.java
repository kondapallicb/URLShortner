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

public class InMemoryUrlMappingRepository implements UrlMappingRepository {

    protected final ConcurrentMap<String, ShortUrl> mappings = new ConcurrentHashMap<>();
    protected final ConcurrentMap<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    protected final ConcurrentMap<String, String> idempotencyPayloads = new ConcurrentHashMap<>();
    protected final ConcurrentMap<String, List<UrlClickEvent>> clicks = new ConcurrentHashMap<>();

    @Override
    public ShortUrl save(ShortUrl shortUrl) {
        if (mappings.putIfAbsent(shortUrl.slug(), shortUrl) != null) {
            throw new com.kondapallicb.urlshortener.domain.SlugConflictException(shortUrl.slug());
        }
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

    @Override public Optional<String> idempotencyPayload(String key) {
        return Optional.ofNullable(idempotencyPayloads.get(key));
    }

    @Override public synchronized ShortUrl reserve(ShortUrl mapping, String key, String payload) {
        ShortUrl saved = save(mapping);
        if (key != null && !key.isBlank()) {
            saveIdempotencyKey(key, saved.slug());
            idempotencyPayloads.put(key, payload);
        }
        return saved;
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
                    lastAccessedAt,
                    events.stream().collect(java.util.stream.Collectors.groupingBy(
                            event -> event.clickedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                            java.util.TreeMap::new, java.util.stream.Collectors.counting()))
            );
        }
    }

    @Override public ShortUrl deactivate(String slug) {
        return mappings.compute(slug, (key, mapping) -> {
            if (mapping == null) throw new com.kondapallicb.urlshortener.domain.UrlMappingNotFoundException(slug);
            return new ShortUrl(mapping.slug(), mapping.longUrl(), mapping.createdAt(), mapping.expiresAt(), false);
        });
    }
}
