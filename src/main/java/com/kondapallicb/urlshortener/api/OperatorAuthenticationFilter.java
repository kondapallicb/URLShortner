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
    private final String securityToken;
    private final String securityOperator;
    public OperatorAuthenticationFilter(String token, String operator) {
        this(token, operator, "", "security-reviewer");
    }
    @org.springframework.beans.factory.annotation.Autowired
    public OperatorAuthenticationFilter(@Value("${app.operator.token:}") String token,
            @Value("${app.operator.name:operator}") String operator,
            @Value("${app.operator.security-token:}") String securityToken,
            @Value("${app.operator.security-name:security-reviewer}") String securityOperator) {
        this.token = token;
        this.operator = operator;
        this.securityToken = securityToken;
        this.securityOperator = securityOperator;
        if (!securityToken.isBlank() && (securityToken.equals(token) || securityOperator.equals(operator))) {
            throw new IllegalArgumentException("Security reviewer must have a separate credential and identity");
        }
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String path = org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!path.startsWith("/api/workflows") && !path.matches("/api/urls/[^/]+/deactivation")) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        boolean operatorAuthenticated = matches(token, header);
        boolean securityAuthenticated = matches(securityToken, header);
        if (!operatorAuthenticated && !securityAuthenticated) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            return;
        }
        if (securityAuthenticated && !request.getMethod().equals("GET") && !path.endsWith("/approvals")) {
            response.setStatus(403);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public Principal getUserPrincipal() { return () -> operatorAuthenticated ? operator : securityOperator; }
            @Override public String getRemoteUser() { return getUserPrincipal().getName(); }
            @Override public boolean isUserInRole(String role) {
                return operatorAuthenticated ? role.equals("OPERATOR") : role.equals("SECURITY_REVIEWER");
            }
        }, response);
    }
    private boolean matches(String credential, String header) {
        return !credential.isBlank() && header != null && MessageDigest.isEqual(
                ("Bearer " + credential).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }
}
