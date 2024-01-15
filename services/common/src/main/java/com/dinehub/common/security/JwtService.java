package com.dinehub.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and validates JWTs.
 *
 * <p>Used by auth-service to issue and by every other service to validate. The
 * gateway validates too — that duplication is deliberate defence in depth: a
 * request that reaches a service by any route other than the gateway is still
 * checked.
 */
public class JwtService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(UUID userId, String email, String role) {
        return issue(userId, Map.of(CLAIM_EMAIL, email, CLAIM_ROLE, role, CLAIM_TYPE, TYPE_ACCESS),
                properties.accessTokenTtl().toSeconds());
    }

    public String issueRefreshToken(UUID userId) {
        return issue(userId, Map.of(CLAIM_TYPE, TYPE_REFRESH),
                properties.refreshTokenTtl().toSeconds());
    }

    private String issue(UUID subject, Map<String, Object> claims, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject.toString())
                .issuer(properties.issuer())
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .id(UUID.randomUUID().toString())
                .signWith(key)
                .compact();
    }

    /**
     * Parses and validates a token.
     *
     * @throws JwtException if the signature, expiry or issuer is wrong. The
     *         caller turns that into a 401 — never into a partial success.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Validates and additionally requires the token to be an access token. */
    public Claims parseAccessToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            // A refresh token presented as an access token must be refused. It
            // has a much longer life, so accepting it would quietly extend every
            // session to the refresh TTL.
            throw new JwtException("Expected an access token");
        }
        return claims;
    }

    public boolean isValid(String token) {
        try {
            parseAccessToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
