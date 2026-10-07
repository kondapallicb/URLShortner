package com.kondapallicb.urlshortener.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kondapallicb.urlshortener.domain.ShortUrl;
import com.kondapallicb.urlshortener.domain.SlugGenerator;
import com.kondapallicb.urlshortener.domain.UrlMappingExpiredException;
import com.kondapallicb.urlshortener.domain.UrlMappingRepository;
import com.kondapallicb.urlshortener.infrastructure.InMemoryUrlMappingRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class DefaultUrlShorteningServiceTest {

    private final UrlMappingRepository repository = new InMemoryUrlMappingRepository();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T18:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsMappingWithGeneratedSlugAndDefaultExpiry() {
        DefaultUrlShorteningService service = new DefaultUrlShorteningService(
                repository,
                new StubSlugGenerator("abc123Z"),
                clock
        );

        ShortUrl shortUrl = service.create(new CreateShortUrlCommand(
                URI.create("https://example.com"),
                null,
                null
        ));

        assertThat(shortUrl.slug()).isEqualTo("abc123Z");
        assertThat(shortUrl.longUrl()).isEqualTo(URI.create("https://example.com"));
        assertThat(shortUrl.expiresAt()).isEqualTo(Instant.parse("2026-11-05T18:00:00Z"));
        assertThat(service.resolve("abc123Z")).isEqualTo(URI.create("https://example.com"));
    }

    @Test
    void retriesWhenGeneratedSlugAlreadyExists() {
        DefaultUrlShorteningService service = new DefaultUrlShorteningService(
                repository,
                new StubSlugGenerator("taken01", "free002"),
                clock
        );
        repository.save(new ShortUrl(
                "taken01",
                URI.create("https://existing.example"),
                Instant.parse("2026-10-06T18:00:00Z"),
                Instant.parse("2026-11-05T18:00:00Z")
        ));

        ShortUrl shortUrl = service.create(new CreateShortUrlCommand(
                URI.create("https://new.example"),
                120L,
                null
        ));

        assertThat(shortUrl.slug()).isEqualTo("free002");
        assertThat(shortUrl.expiresAt()).isEqualTo(Instant.parse("2026-10-06T18:02:00Z"));
    }

    @Test
    void rejectsExpiredMappingOnResolve() {
        DefaultUrlShorteningService service = new DefaultUrlShorteningService(
                repository,
                new StubSlugGenerator("expired"),
                clock
        );
        repository.save(new ShortUrl(
                "expired",
                URI.create("https://old.example"),
                Instant.parse("2026-10-01T18:00:00Z"),
                Instant.parse("2026-10-06T18:00:00Z")
        ));

        assertThatThrownBy(() -> service.resolve("expired"))
                .isInstanceOf(UrlMappingExpiredException.class);
    }

    @Test
    void reusesMappingForIdempotencyKey() {
        DefaultUrlShorteningService service = new DefaultUrlShorteningService(
                repository,
                new StubSlugGenerator("first01", "second2"),
                clock
        );

        ShortUrl first = service.create(new CreateShortUrlCommand(
                URI.create("https://example.com"),
                null,
                "request-123"
        ));
        ShortUrl second = service.create(new CreateShortUrlCommand(
                URI.create("https://example.com"),
                null,
                "request-123"
        ));

        assertThat(second.slug()).isEqualTo(first.slug());
    }

    @Test
    void recordsClickAnalyticsWhenResolvingRedirect() {
        DefaultUrlShorteningService service = new DefaultUrlShorteningService(
                repository,
                new StubSlugGenerator("click01"),
                clock
        );
        ShortUrl shortUrl = service.create(new CreateShortUrlCommand(
                URI.create("https://example.com"),
                null,
                null
        ));

        service.resolveAndRecordClick(shortUrl.slug(), new RecordClickCommand(
                "127.0.0.1",
                "JUnit",
                "https://referrer.example"
        ));

        assertThat(service.analytics(shortUrl.slug()).totalClicks()).isEqualTo(1);
        assertThat(service.analytics(shortUrl.slug()).lastAccessedAt())
                .isEqualTo(Instant.parse("2026-10-06T18:00:00Z"));
    }

    private static final class StubSlugGenerator implements SlugGenerator {

        private final Queue<String> values = new ArrayDeque<>();

        private StubSlugGenerator(String... values) {
            this.values.addAll(java.util.List.of(values));
        }

        @Override
        public String generate() {
            return values.remove();
        }
    }
}
