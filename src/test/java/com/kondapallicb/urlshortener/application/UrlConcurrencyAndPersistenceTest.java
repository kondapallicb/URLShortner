package com.kondapallicb.urlshortener.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.domain.*;
import com.kondapallicb.urlshortener.infrastructure.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class UrlConcurrencyAndPersistenceTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T23:59:59Z"), ZoneOffset.UTC);

    @Test void concurrentIdempotentCreatesReturnOneMappingAndRejectDifferentPayloads() throws Exception {
        var repository = new InMemoryUrlMappingRepository();
        var sequence = new AtomicInteger();
        var first = new DefaultUrlShorteningService(repository, () -> "slug" + sequence.incrementAndGet(), clock);
        var second = new DefaultUrlShorteningService(repository, () -> "slug" + sequence.incrementAndGet(), clock);
        var command = new CreateShortUrlCommand(URI.create("https://example.org"), null, "shared");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<ShortUrl>>();
            for (int index = 0; index < 32; index++) {
                var service = index % 2 == 0 ? first : second;
                futures.add(executor.submit(() -> service.create(command)));
            }
            for (var future : futures) assertThat(future.get().slug()).isEqualTo("slug1");
        }
        assertThat(sequence.get()).isEqualTo(1);
        assertThatThrownBy(() -> first.create(new CreateShortUrlCommand(URI.create("https://different.org"), null, "shared")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> second.create(new CreateShortUrlCommand(command.longUrl(), 60L, "shared")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test void atomicCollisionDoesNotOverwriteTheWinner() throws Exception {
        var repository = new InMemoryUrlMappingRepository();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int index = 0; index < 2; index++) {
                final int value = index;
                tasks.add(executor.submit(() -> {
                    barrier.await();
                    try {
                        repository.save(new ShortUrl("same", URI.create("https://example.org/" + value),
                                clock.instant(), clock.instant().plusSeconds(60)));
                        return true;
                    } catch (SlugConflictException conflict) { return false; }
                }));
            }
            assertThat(tasks.get(0).get() ^ tasks.get(1).get()).isTrue();
        }
        assertThat(repository.findBySlug("same")).isPresent();
    }

    @Test void restartRetainsAliasIdempotencyDeactivationAndUtcDailyCounts() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var repository = new DurableUrlMappingRepository(directory.toString(), mapper);
        var service = new DefaultUrlShorteningService(repository, () -> "unused", clock);
        var command = new CreateShortUrlCommand(URI.create("https://example.org"), null, "key", "campaign");
        service.create(command);
        repository.recordClick(new UrlClickEvent("campaign", clock.instant(), "ip", "agent", null));
        repository.recordClick(new UrlClickEvent("campaign", clock.instant().plusSeconds(2), "ip", "agent", null));
        service.deactivate("campaign");
        var restored = new DurableUrlMappingRepository(directory.toString(), mapper);
        var restarted = new DefaultUrlShorteningService(restored, () -> "unused2", clock);
        assertThat(restarted.create(command).slug()).isEqualTo("campaign");
        assertThatThrownBy(() -> restarted.resolve("campaign")).isInstanceOf(UrlMappingExpiredException.class);
        assertThat(restarted.analytics("campaign").dailyClicksUtc()).containsEntry(LocalDate.parse("2026-10-06"), 1L)
                .containsEntry(LocalDate.parse("2026-10-07"), 1L);
    }
}
