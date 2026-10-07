package com.kondapallicb.urlshortener.application;

import com.kondapallicb.urlshortener.domain.ShortUrl;
import java.net.URI;

public interface UrlShorteningService {

    ShortUrl create(CreateShortUrlCommand command);

    URI resolve(String slug);
}
