package com.kondapallicb.urlshortener.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final ConcurrentMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowSeconds;
    private final Clock clock;

    public RateLimitingFilter(
            @Value("${app.rate-limit.max-requests:60}") int maxRequests,
            @Value("${app.rate-limit.window-seconds:60}") long windowSeconds,
            Clock clock
    ) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isApiRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String clientKey = resolveClientKey(request);
        if (!allow(clientKey)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests","details":[]}
                    """);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allow(String clientKey) {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minusSeconds(windowSeconds);
        Deque<Instant> requests = requestLog.computeIfAbsent(clientKey, key -> new ArrayDeque<>());
        synchronized (requests) {
            while (!requests.isEmpty() && requests.peekFirst().isBefore(cutoff)) {
                requests.removeFirst();
            }
            if (requests.size() >= maxRequests) {
                return false;
            }
            requests.addLast(now);
            return true;
        }
    }

    private boolean isApiRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    private String resolveClientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
