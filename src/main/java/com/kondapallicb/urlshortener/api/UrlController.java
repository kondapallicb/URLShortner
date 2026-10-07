package com.kondapallicb.urlshortener.api;

import com.kondapallicb.urlshortener.application.CreateShortUrlCommand;
import com.kondapallicb.urlshortener.application.RecordClickCommand;
import com.kondapallicb.urlshortener.application.UrlShorteningService;
import com.kondapallicb.urlshortener.domain.UrlAnalytics;
import com.kondapallicb.urlshortener.domain.ShortUrl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping
public class UrlController {

    private final UrlShorteningService urlShorteningService;

    public UrlController(UrlShorteningService urlShorteningService) {
        this.urlShorteningService = urlShorteningService;
    }

    @PostMapping("/api/urls")
    public ResponseEntity<CreateShortUrlResponse> create(
            @Valid @RequestBody CreateShortUrlRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        ShortUrl shortUrl = urlShorteningService.create(new CreateShortUrlCommand(
                URI.create(request.longUrl()),
                request.ttlSeconds(),
                idempotencyKey
        ));
        String shortLink = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{slug}")
                .buildAndExpand(shortUrl.slug())
                .toUriString();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateShortUrlResponse(
                        shortUrl.slug(),
                        shortLink,
                        shortUrl.longUrl().toString(),
                        shortUrl.createdAt(),
                        shortUrl.expiresAt()
                ));
    }

    @GetMapping("/{slug}")
    public void redirect(
            @PathVariable String slug,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        URI longUrl = urlShorteningService.resolveAndRecordClick(slug, new RecordClickCommand(
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),
                request.getHeader("Referer")
        ));
        response.sendRedirect(longUrl.toString());
    }

    @GetMapping("/api/urls/{slug}/analytics")
    public UrlAnalytics analytics(@PathVariable String slug) {
        return urlShorteningService.analytics(slug);
    }
}
