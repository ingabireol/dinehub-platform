package com.dinehub.gateway;

import com.dinehub.common.security.JwtProperties;
import com.dinehub.common.security.JwtService;
import com.dinehub.common.security.Roles;
import com.dinehub.gateway.filter.JwtAuthenticationGatewayFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationGatewayFilterTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough-for-hs256";

    private JwtService jwt;
    private JwtAuthenticationGatewayFilter filter;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jwt = new JwtService(new JwtProperties(
                SECRET, Duration.ofMinutes(15), Duration.ofDays(7), "dinehub"));
        filter = new JwtAuthenticationGatewayFilter(jwt);
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("a valid token is translated into identity headers")
    void passesIdentityDownstream() {
        String token = jwt.issueAccessToken(userId, "chef@dinehub.local", Roles.KITCHEN);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        var headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst(JwtAuthenticationGatewayFilter.HEADER_USER_ID))
                .isEqualTo(userId.toString());
        assertThat(headers.getFirst(JwtAuthenticationGatewayFilter.HEADER_USER_ROLE))
                .isEqualTo(Roles.KITCHEN);
        assertThat(headers.getFirst(JwtAuthenticationGatewayFilter.HEADER_TRACE_ID)).isNotBlank();
    }

    @Test
    @DisplayName("client-supplied identity headers are stripped")
    void stripsSpoofedIdentityHeaders() {
        // Without this, impersonating an admin is one curl away.
        String token = jwt.issueAccessToken(userId, "customer@dinehub.local", Roles.CUSTOMER);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(JwtAuthenticationGatewayFilter.HEADER_USER_ID, "00000000-0000-0000-0000-000000000000")
                .header(JwtAuthenticationGatewayFilter.HEADER_USER_ROLE, Roles.ADMIN));

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        var headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst(JwtAuthenticationGatewayFilter.HEADER_USER_ROLE))
                .isEqualTo(Roles.CUSTOMER)
                .isNotEqualTo(Roles.ADMIN);
        assertThat(headers.get(JwtAuthenticationGatewayFilter.HEADER_USER_ID)).hasSize(1);
    }

    @Test
    @DisplayName("a request with no token to a protected path is refused with 401")
    void rejectsMissingToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an invalid token is refused with 401")
    void rejectsInvalidToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("login is reachable without a token")
    void allowsPublicAuthEndpoints() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login"));

        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            reached.set(true);
            return Mono.empty();
        }).block();

        assertThat(reached.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("browsing the menu is public but changing it is not")
    void menuIsReadableButNotWritableWithoutAToken() {
        var read = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/menu/items"));
        AtomicReference<Boolean> readReached = new AtomicReference<>(false);
        filter.filter(read, ex -> {
            readReached.set(true);
            return Mono.empty();
        }).block();
        assertThat(readReached.get()).isTrue();

        var write = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/menu/items"));
        filter.filter(write, ex -> Mono.empty()).block();
        assertThat(write.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an incoming trace id is preserved across the hop")
    void preservesIncomingTraceId() {
        String token = jwt.issueAccessToken(userId, "a@b.c", Roles.CUSTOMER);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(JwtAuthenticationGatewayFilter.HEADER_TRACE_ID, "trace-from-client"));

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        assertThat(captured.get().getRequest().getHeaders()
                .getFirst(JwtAuthenticationGatewayFilter.HEADER_TRACE_ID))
                .isEqualTo("trace-from-client");
    }

    @Test
    @DisplayName("a refresh token cannot be used as an access token at the edge")
    void rejectsRefreshTokenAsAccess() {
        String refresh = jwt.issueRefreshToken(userId);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
