package com.kondapallicb.urlshortener.application;

import com.kondapallicb.urlshortener.domain.ShortUrl;
import com.kondapallicb.urlshortener.domain.SlugGenerator;
import com.kondapallicb.urlshortener.domain.UrlMappingExpiredException;
import com.kondapallicb.urlshortener.domain.UrlMappingNotFoundException;
import com.kondapallicb.urlshortener.domain.UrlMappingRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class DefaultUrlShorteningService implements UrlShorteningService {

    private static final Duration DEFAULT_TTL = Duration.ofDays(30);
    private static final int MAX_SLUG_ATTEMPTS = 10;

    private final UrlMappingRepository repository;
    private final SlugGenerator slugGenerator;
    private final Clock clock;

    public DefaultUrlShorteningService(
            UrlMappingRepository repository,
            SlugGenerator slugGenerator,
            Clock clock
    ) {
        this.repository = repository;
        this.slugGenerator = slugGenerator;
        this.clock = clock;
    }

    @Override
    public ShortUrl create(CreateShortUrlCommand command) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(resolveTtl(command.ttlSeconds()));
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String slug = slugGenerator.generate();
            if (repository.findBySlug(slug).isEmpty()) {
                return repository.save(new ShortUrl(slug, command.longUrl(), now, expiresAt));
            }
        }
        throw new IllegalStateException("Unable to allocate a unique short URL slug");
    }

    @Override
    public URI resolve(String slug) {
        ShortUrl shortUrl = repository.findBySlug(slug)
                .orElseThrow(() -> new UrlMappingNotFoundException(slug));
        if (shortUrl.isExpired(Instant.now(clock))) {
            throw new UrlMappingExpiredException(slug);
        }
        return shortUrl.longUrl();
    }

    private Duration resolveTtl(Long ttlSeconds) {
        if (ttlSeconds == null) {
            return DEFAULT_TTL;
        }
        return Duration.ofSeconds(ttlSeconds);
    }
}
