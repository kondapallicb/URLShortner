package com.kondapallicb.urlshortener.domain;

public class UrlMappingNotFoundException extends RuntimeException {

    public UrlMappingNotFoundException(String slug) {
        super("Short URL was not found: " + slug);
    }
}
