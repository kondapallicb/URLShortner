package com.kondapallicb.urlshortener.observability;

import java.time.Instant;

public record SystemStatus(String status, String service, Instant checkedAt) {
}
