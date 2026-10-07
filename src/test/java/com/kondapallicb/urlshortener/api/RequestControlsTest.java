package com.kondapallicb.urlshortener.api;

import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.assertThat;

class RequestControlsTest {
    @Test void rotatedForwardedHeadersDoNotBypassLimit() throws Exception {
        var filter = new RateLimitingFilter(1, 60, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var count = new AtomicInteger();
        for (int index = 0; index < 2; index++) {
            var request = new MockHttpServletRequest("POST", "/api/urls");
            request.setRemoteAddr("192.0.2.1");
            request.addHeader("X-Forwarded-For", "198.51.100." + index);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> count.incrementAndGet());
            if (index == 1) {
                assertThat(response.getStatus()).isEqualTo(429);
                assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            }
        }
        assertThat(count.get()).isEqualTo(1);
    }
    @Test void operatorIdentityComesFromConfiguredCredential() throws Exception {
        var filter = new OperatorAuthenticationFilter("test-only-token", "reviewer");
        var request = new MockHttpServletRequest("POST", "/api/workflows/run/approvals");
        var denied = new MockHttpServletResponse();
        filter.doFilter(request, denied, (req, res) -> { throw new AssertionError("Unauthorized request passed"); });
        assertThat(denied.getStatus()).isEqualTo(401);
        request.addHeader("Authorization", "Bearer test-only-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(((jakarta.servlet.http.HttpServletRequest) req).getUserPrincipal().getName()).isEqualTo("reviewer"));
    }
}
