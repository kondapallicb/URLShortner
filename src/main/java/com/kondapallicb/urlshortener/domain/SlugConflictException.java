package com.kondapallicb.urlshortener.domain;

public class SlugConflictException extends IllegalStateException {
    public SlugConflictException(String slug) { super("Slug already exists: " + slug); }
}
