package com.kondapallicb.urlshortener.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.domain.ShortUrl;
import com.kondapallicb.urlshortener.domain.UrlClickEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class DurableUrlMappingRepository extends InMemoryUrlMappingRepository {
    public record Snapshot(Map<String, ShortUrl> mappings, Map<String, String> keys, Map<String, String> payloads,
            Map<String, List<UrlClickEvent>> clicks) { }
    private final Path file;
    private final ObjectMapper mapper;
    public DurableUrlMappingRepository(@Value("${app.storage.directory:./data}") String directory, ObjectMapper mapper) {
        this.file = Path.of(directory).toAbsolutePath().resolve("urls.json");
        this.mapper = mapper;
        if (Files.exists(file)) {
            try { restore(mapper.readValue(file.toFile(), Snapshot.class)); }
            catch (IOException exception) { throw new IllegalStateException("Cannot restore URL data", exception); }
        }
    }
    private Snapshot snapshot() {
        Map<String, List<UrlClickEvent>> events = new HashMap<>();
        clicks.forEach((slug, values) -> { synchronized (values) { events.put(slug, new ArrayList<>(values)); } });
        return new Snapshot(new HashMap<>(mappings), new HashMap<>(idempotencyKeys), new HashMap<>(idempotencyPayloads), events);
    }
    private void restore(Snapshot snapshot) {
        mappings.clear(); mappings.putAll(snapshot.mappings());
        idempotencyKeys.clear(); idempotencyKeys.putAll(snapshot.keys());
        idempotencyPayloads.clear();
        if (snapshot.payloads() != null) idempotencyPayloads.putAll(snapshot.payloads());
        clicks.clear(); snapshot.clicks().forEach((slug, values) -> clicks.put(slug, new ArrayList<>(values)));
    }
    private void persist(Snapshot before) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "urls-", ".tmp");
            mapper.writeValue(temporary.toFile(), snapshot());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            restore(before);
            throw new IllegalStateException("Cannot persist URL data", exception);
        }
    }
    @Override public synchronized ShortUrl save(ShortUrl mapping) {
        Snapshot before = snapshot();
        ShortUrl saved = super.save(mapping);
        persist(before);
        return saved;
    }
    @Override public synchronized ShortUrl reserve(ShortUrl mapping, String key, String payload) {
        Snapshot before = snapshot();
        ShortUrl saved = super.save(mapping);
        if (key != null && !key.isBlank()) {
            super.saveIdempotencyKey(key, saved.slug());
            idempotencyPayloads.put(key, payload);
        }
        persist(before);
        return saved;
    }
    @Override public synchronized void saveIdempotencyKey(String key, String slug) {
        Snapshot before = snapshot();
        super.saveIdempotencyKey(key, slug);
        persist(before);
    }
    @Override public synchronized void recordClick(UrlClickEvent event) {
        Snapshot before = snapshot();
        super.recordClick(event);
        persist(before);
    }
    @Override public synchronized ShortUrl deactivate(String slug) {
        Snapshot before = snapshot();
        ShortUrl mapping = super.deactivate(slug);
        persist(before);
        return mapping;
    }
    @Override public synchronized java.util.Optional<ShortUrl> findBySlug(String slug) {
        return super.findBySlug(slug);
    }
    @Override public synchronized java.util.Optional<ShortUrl> findByIdempotencyKey(String key) {
        return super.findByIdempotencyKey(key);
    }
    @Override public synchronized java.util.Optional<String> idempotencyPayload(String key) {
        return super.idempotencyPayload(key);
    }
    @Override public synchronized com.kondapallicb.urlshortener.domain.UrlAnalytics analyticsFor(String slug) {
        return super.analyticsFor(slug);
    }
}
