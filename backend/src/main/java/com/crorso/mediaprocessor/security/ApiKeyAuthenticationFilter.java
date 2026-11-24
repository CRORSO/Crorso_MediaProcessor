package com.crorso.mediaprocessor.security;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.dto.ErrorResponse;
import com.crorso.mediaprocessor.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Requires the configured API key on every {@code /api/**} request. Keys are compared as SHA-256
 * digests with {@link MessageDigest#isEqual(byte[], byte[])}, so the comparison is constant-time
 * and independent of the presented key's length. Keys are never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final byte[] expectedDigest;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(MediaProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("API_KEY is not configured; every /api/** request will be rejected");
            this.expectedDigest = null;
        } else {
            this.expectedDigest = sha256(apiKey);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAuthorized(request)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("Rejected unauthenticated {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(ErrorCode.UNAUTHORIZED.status().value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage(), RequestIdFilter.currentRequestId(request)));
    }

    private boolean isAuthorized(HttpServletRequest request) {
        if (expectedDigest == null) {
            return false;
        }
        String presented = presentedKey(request);
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, sha256(presented));
    }

    private static String presentedKey(HttpServletRequest request) {
        String headerValue = request.getHeader(API_KEY_HEADER);
        if (headerValue != null) {
            return headerValue.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
