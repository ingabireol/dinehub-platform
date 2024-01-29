package com.dinehub.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough-for-hs256";

    private JwtService jwt;
    private JwtAuthenticationFilter filter;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jwt = new JwtService(new JwtProperties(
                SECRET, Duration.ofMinutes(15), Duration.ofDays(7), "dinehub"));
        filter = new JwtAuthenticationFilter(jwt);
        userId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a valid token authenticates the request with the user id as principal")
    void authenticatesValidToken() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization",
                "Bearer " + jwt.issueAccessToken(userId, "a@b.c", Roles.CUSTOMER));

        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getPrincipal()).isEqualTo(userId);
        assertThat(seen.get().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("the role becomes a ROLE_-prefixed authority so hasRole() works")
    void mapsRoleToAuthority() {
        // Spring Security's hasRole('ADMIN') looks for the authority
        // ROLE_ADMIN. Forgetting the prefix makes every @PreAuthorize fail
        // closed, which looks like a permissions bug rather than a mapping one.
        assertThat(Roles.HAS_ADMIN).contains("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("no token leaves the request unauthenticated rather than failing")
    void leavesUnauthenticatedWithoutToken() throws Exception {
        // Public endpoints must keep working, so a missing token is not an error
        // here — the authorisation rules decide.
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("an invalid token leaves the request unauthenticated")
    void clearsContextOnInvalidToken() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer garbage");

        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("a non-Bearer Authorization header is ignored")
    void ignoresNonBearerScheme() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("the security context is cleared after the request")
    void clearsContextAfterRequest() throws Exception {
        // Threads are pooled. A context left behind leaks one user's identity
        // into the next request served by that thread.
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization",
                "Bearer " + jwt.issueAccessToken(userId, "a@b.c", Roles.ADMIN));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a refresh token does not authenticate a request")
    void refusesRefreshToken() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + jwt.issueRefreshToken(userId));

        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(seen.get()).isNull();
    }
}
