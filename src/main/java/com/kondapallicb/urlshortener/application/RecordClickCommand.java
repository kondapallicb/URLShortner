package com.kondapallicb.urlshortener.application;

public record RecordClickCommand(String clientIp, String userAgent, String referer) {
}
