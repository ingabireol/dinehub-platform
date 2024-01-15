package com.dinehub.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough-for-hs256";

    private JwtService jwt;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jwt = new JwtService(new JwtProperties(
                SECRET, Duration.ofMinutes(15), Duration.ofDays(7), "dinehub"));
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("an issued access token carries the subject, email and role")
    void issuesAccessTokenWithClaims() {
        String token = jwt.issueAccessToken(userId, "chef@dinehub.local", Roles.KITCHEN);

        Claims claims = jwt.parseAccessToken(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get(JwtService.CLAIM_EMAIL, String.class)).isEqualTo("chef@dinehub.local");
        assertThat(claims.get(JwtService.CLAIM_ROLE, String.class)).isEqualTo(Roles.KITCHEN);
        assertThat(claims.getIssuer()).isEqualTo("dinehub");
    }

    @Test
    @DisplayName("a refresh token is refused where an access token is required")
    void refreshTokenIsNotAcceptedAsAccessToken() {
        // This is the check that stops a long-lived refresh token being used to
        // extend every session to the refresh TTL.
        String refresh = jwt.issueRefreshToken(userId);

        assertThatThrownBy(() -> jwt.parseAccessToken(refresh))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("access token");
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void rejectsTokenSignedWithAnotherKey() {
        JwtService other = new JwtService(new JwtProperties(
                "a-completely-different-secret-also-long-enough-ok", Duration.ofMinutes(15),
                Duration.ofDays(7), "dinehub"));
        String foreign = other.issueAccessToken(userId, "a@b.c", Roles.CUSTOMER);

        assertThat(jwt.isValid(foreign)).isFalse();
    }

    @Test
    @DisplayName("a token from a different issuer is rejected")
    void rejectsTokenFromAnotherIssuer() {
        JwtService other = new JwtService(new JwtProperties(
                SECRET, Duration.ofMinutes(15), Duration.ofDays(7), "somewhere-else"));
        String foreign = other.issueAccessToken(userId, "a@b.c", Roles.CUSTOMER);

        assertThat(jwt.isValid(foreign)).isFalse();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpiredToken() {
        JwtService shortLived = new JwtService(new JwtProperties(
                SECRET, Duration.ofSeconds(-1), Duration.ofDays(7), "dinehub"));
        String expired = shortLived.issueAccessToken(userId, "a@b.c", Roles.CUSTOMER);

        assertThat(jwt.isValid(expired)).isFalse();
    }

    @Test
    @DisplayName("garbage is rejected rather than throwing something unhandled")
    void rejectsGarbage() {
        assertThat(jwt.isValid("not-a-token")).isFalse();
        assertThat(jwt.isValid("")).isFalse();
    }

    @Test
    @DisplayName("configuration without a secret refuses to start")
    void requiresASecret() {
        // A default signing key is exactly the kind of thing that reaches
        // production unnoticed, so there is not one.
        assertThatThrownBy(() -> new JwtProperties(null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dinehub.jwt.secret");
    }

    @Test
    @DisplayName("configuration with too short a secret refuses to start")
    void requiresALongEnoughSecret() {
        assertThatThrownBy(() -> new JwtProperties("short", null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("token TTLs default sensibly when not configured")
    void appliesDefaults() {
        JwtProperties props = new JwtProperties(SECRET, null, null, null);
        assertThat(props.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(props.issuer()).isEqualTo("dinehub");
    }
}
