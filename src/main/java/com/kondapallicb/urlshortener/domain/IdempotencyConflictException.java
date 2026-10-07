package com.kondapallicb.urlshortener.domain;

public class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException() { super("Idempotency key was used for a different payload"); }
}
