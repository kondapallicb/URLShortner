package com.kondapallicb.urlshortener.domain;

public class UrlMappingExpiredException extends RuntimeException {

    public UrlMappingExpiredException(String slug) {
        super("Short URL has expired: " + slug);
    }
}
