package com.kondapallicb.urlshortener.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.URL;

public record CreateShortUrlRequest(
        @NotBlank
        @URL
        @Pattern(regexp = "https?://.*", message = "must start with http:// or https://")
        String longUrl,

        @Min(60)
        @Max(31_536_000)
        Long ttlSeconds,
        @Pattern(regexp = "[A-Za-z0-9_-]{3,64}") String customAlias
) {
}
