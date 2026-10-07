package com.kondapallicb.urlshortener.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
public class OperatorAuthenticationFilter extends OncePerRequestFilter {
    private final String token;
    private final String operator;
    public OperatorAuthenticationFilter(@Value("${app.operator.token:}") String token,
            @Value("${app.operator.name:operator}") String operator) {
        this.token = token;
        this.operator = operator;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/workflows")
                && !request.getRequestURI().matches("/api/urls/[^/]+/deactivation")) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (token.isBlank() || header == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public Principal getUserPrincipal() { return () -> operator; }
            @Override public String getRemoteUser() { return operator; }
        }, response);
    }
}
