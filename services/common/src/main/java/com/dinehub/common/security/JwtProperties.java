package com.dinehub.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT configuration, bound from the environment.
 *
 * <p>The signing secret has no default. A default signing key is the kind of
 * thing that ships to production because nobody noticed it was still there, so
 * the application refuses to start without one rather than running insecurely.
 */
@ConfigurationProperties(prefix = "dinehub.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String issuer
) {

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "dinehub.jwt.secret is not set. It is injected from a Kubernetes Secret in "
                    + "every deployed environment; there is deliberately no default.");
        }
        // HS256 needs at least 256 bits of key. A shorter one is accepted by some
        // libraries and is not secure.
        if (secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "dinehub.jwt.secret must be at least 32 bytes for HS256 (got "
                    + secret.getBytes().length + ").");
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(7);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "dinehub";
        }
    }
}
