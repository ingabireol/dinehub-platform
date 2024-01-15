package com.dinehub.gateway.filter;

import com.dinehub.common.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Validates the bearer token at the edge and passes the identity downstream.
 *
 * <p>Two things it is careful about:
 *
 * <ol>
 *   <li><b>It strips incoming identity headers.</b> A client that sends its own
 *       {@code X-User-Id} must not be believed. Without this, impersonation is a
 *       single curl away.
 *   <li><b>It is not the only check.</b> Services validate the token too. If a
 *       request reaches a service by some route that bypasses the gateway, it is
 *       still authenticated.
 * </ol>
 */
@Component
public class JwtAuthenticationGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationGatewayFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** Reachable without a token. Everything else needs one. */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/menu/categories",
            "/api/v1/menu/items",
            "/api/v1/menu/items/*",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/swagger-ui/**");

    private final JwtService jwt;

    public JwtAuthenticationGatewayFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        String traceId = request.getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // Public GETs on the menu only. A POST to the same path is a write.
        if (isPublic(path, request.getMethod().name())) {
            return chain.filter(exchange.mutate()
                    .request(stripIdentityHeaders(request).header(HEADER_TRACE_ID, traceId).build())
                    .build());
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorised(exchange, "Missing bearer token", traceId);
        }

        try {
            Claims claims = jwt.parseAccessToken(authorization.substring(7));

            ServerHttpRequest mutated = stripIdentityHeaders(request)
                    .header(HEADER_USER_ID, claims.getSubject())
                    .header(HEADER_USER_ROLE, claims.get(JwtService.CLAIM_ROLE, String.class))
                    .header(HEADER_USER_EMAIL, claims.get(JwtService.CLAIM_EMAIL, String.class))
                    .header(HEADER_TRACE_ID, traceId)
                    .build();

            return chain.filter(exchange.mutate().request(mutated).build());

        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected token on {} [traceId={}]: {}", path, traceId, e.getMessage());
            return unauthorised(exchange, "Invalid or expired token", traceId);
        }
    }

    /**
     * Removes any identity headers the client supplied before we set our own.
     * This is the control that stops header-spoofed impersonation.
     */
    private ServerHttpRequest.Builder stripIdentityHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_ROLE);
                    headers.remove(HEADER_USER_EMAIL);
                });
    }

    private boolean isPublic(String path, String method) {
        boolean matches = PUBLIC_PATHS.stream().anyMatch(p -> MATCHER.match(p, path));
        if (!matches) {
            return false;
        }
        // Menu paths are public to read and protected to write.
        if (path.startsWith("/api/v1/menu")) {
            return "GET".equals(method);
        }
        return true;
    }

    private Mono<Void> unauthorised(ServerWebExchange exchange, String message, String traceId) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(HEADER_TRACE_ID, traceId);

        // The same ApiError shape the services return, so the client has one
        // error handler rather than a special case for the gateway.
        String body = """
                {"timestamp":"%s","status":401,"error":"Unauthorized",\
                "message":"%s","path":"%s","traceId":"%s"}"""
                .formatted(Instant.now(), message, exchange.getRequest().getURI().getPath(), traceId);

        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        // Before routing, after the trace id is established.
        return -100;
    }
}
