package com.dinehub.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Request and response shapes for the auth API.
 *
 * <p>Validation lives on the DTO rather than in the service, so a malformed
 * request is rejected with a field-level error before any business logic runs.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 160)
            String email,

            // 12 characters minimum with mixed classes. Long enough to resist
            // offline cracking of the BCrypt hash if the database ever leaks.
            @NotBlank
            @Size(min = 12, max = 72, message = "must be between 12 and 72 characters")
            @Pattern(
                    regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
                    message = "must contain a lowercase letter, an uppercase letter and a digit")
            String password,

            @NotBlank @Size(max = 120)
            String fullName,

            // Deliberately not accepted from the client — see AuthService.
            String role
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {
    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds,
            UserResponse user
    ) {
        public static TokenResponse of(String access, String refresh, long ttl, UserResponse user) {
            return new TokenResponse(access, refresh, "Bearer", ttl, user);
        }
    }

    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            String role,
            Instant createdAt
    ) {
    }
}
