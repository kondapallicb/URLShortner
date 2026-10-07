package com.kondapallicb.urlshortener.application;

import com.kondapallicb.urlshortener.domain.ShortUrl;
import com.kondapallicb.urlshortener.domain.SlugGenerator;
import com.kondapallicb.urlshortener.domain.UrlAnalytics;
import com.kondapallicb.urlshortener.domain.UrlClickEvent;
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
        if (hasIdempotencyKey(command)) {
            return repository.findByIdempotencyKey(command.idempotencyKey())
                    .orElseGet(() -> createNewMapping(command));
        }
        return createNewMapping(command);
    }

    @Override
    public URI resolve(String slug) {
        return findActiveMapping(slug).longUrl();
    }

    @Override
    public URI resolveAndRecordClick(String slug, RecordClickCommand command) {
        ShortUrl shortUrl = findActiveMapping(slug);
        repository.recordClick(new UrlClickEvent(
                shortUrl.slug(),
                Instant.now(clock),
                command.clientIp(),
                command.userAgent(),
                command.referer()
        ));
        return shortUrl.longUrl();
    }

    @Override
    public UrlAnalytics analytics(String slug) {
        findActiveMapping(slug);
        return repository.analyticsFor(slug);
    }

    private ShortUrl createNewMapping(CreateShortUrlCommand command) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(resolveTtl(command.ttlSeconds()));
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String slug = slugGenerator.generate();
            if (repository.findBySlug(slug).isEmpty()) {
                ShortUrl saved = repository.save(new ShortUrl(slug, command.longUrl(), now, expiresAt));
                if (hasIdempotencyKey(command)) {
                    repository.saveIdempotencyKey(command.idempotencyKey(), saved.slug());
                }
                return saved;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique short URL slug");
    }

    private ShortUrl findActiveMapping(String slug) {
        ShortUrl shortUrl = repository.findBySlug(slug)
                .orElseThrow(() -> new UrlMappingNotFoundException(slug));
        if (shortUrl.isExpired(Instant.now(clock))) {
            throw new UrlMappingExpiredException(slug);
        }
        return shortUrl;
    }

    private Duration resolveTtl(Long ttlSeconds) {
        if (ttlSeconds == null) {
            return DEFAULT_TTL;
        }
        return Duration.ofSeconds(ttlSeconds);
    }

    private boolean hasIdempotencyKey(CreateShortUrlCommand command) {
        return command.idempotencyKey() != null && !command.idempotencyKey().isBlank();
    }
}
