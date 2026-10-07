package com.kondapallicb.urlshortener.application;

import com.kondapallicb.urlshortener.domain.ShortUrl;
import com.kondapallicb.urlshortener.domain.UrlAnalytics;
import java.net.URI;

public interface UrlShorteningService {

    ShortUrl create(CreateShortUrlCommand command);

    URI resolve(String slug);

    URI resolveAndRecordClick(String slug, RecordClickCommand command);

    UrlAnalytics analytics(String slug);
}
