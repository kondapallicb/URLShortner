package com.kondapallicb.urlshortener.api;

public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String clientKey) {
        super("Rate limit exceeded for client: " + clientKey);
    }
}
