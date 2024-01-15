package com.dinehub.auth.service;

import com.dinehub.auth.dto.AuthDtos;
import com.dinehub.auth.entity.User;
import com.dinehub.auth.repository.UserRepository;
import com.dinehub.common.api.ApiExceptions;
import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.common.security.JwtProperties;
import com.dinehub.common.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Registration, login and token refresh.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final JwtProperties jwtProperties;
    private final RabbitTemplate rabbit;

    public AuthService(UserRepository users,
                       PasswordEncoder passwordEncoder,
                       JwtService jwt,
                       JwtProperties jwtProperties,
                       RabbitTemplate rabbit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.jwtProperties = jwtProperties;
        this.rabbit = rabbit;
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest request) {
        String email = request.email().toLowerCase().trim();

        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiExceptions.ConflictException("An account with that email already exists");
        }

        // Self-registration always produces a CUSTOMER, whatever the request
        // body says. Honouring a client-supplied role would be privilege
        // escalation by JSON field — ADMIN and KITCHEN accounts are created by
        // an administrator through a separate path.
        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                User.Role.CUSTOMER);

        users.save(user);
        log.info("Registered user {} ({})", user.getId(), user.getRole());

        publishUserRegistered(user);
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        User user = users.findByEmailIgnoreCase(request.email().trim())
                .orElse(null);

        // The same failure message and roughly the same work whether the account
        // exists or not. Distinguishing them turns the login endpoint into an
        // account enumeration oracle.
        if (user == null) {
            passwordEncoder.encode(request.password());
            throw new ApiExceptions.ForbiddenException("Invalid email or password");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Failed login attempt for {}", user.getId());
            throw new ApiExceptions.ForbiddenException("Invalid email or password");
        }
        if (!user.isEnabled()) {
            throw new ApiExceptions.ForbiddenException("This account is disabled");
        }

        log.info("User {} logged in", user.getId());
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest request) {
        Claims claims;
        try {
            claims = jwt.parse(request.refreshToken());
        } catch (JwtException e) {
            throw new ApiExceptions.ForbiddenException("Invalid or expired refresh token");
        }

        if (!JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
            throw new ApiExceptions.ForbiddenException("Not a refresh token");
        }

        User user = users.findById(UUID.fromString(claims.getSubject()))
                .orElseThrow(() -> new ApiExceptions.ForbiddenException("Account no longer exists"));

        // Checked on every refresh, not only at login. Otherwise a disabled
        // account keeps working for the whole refresh-token lifetime.
        if (!user.isEnabled()) {
            throw new ApiExceptions.ForbiddenException("This account is disabled");
        }

        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserResponse currentUser(UUID userId) {
        return users.findById(userId)
                .map(AuthService::toResponse)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("User", userId));
    }

    private AuthDtos.TokenResponse issueTokens(User user) {
        String access = jwt.issueAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refresh = jwt.issueRefreshToken(user.getId());
        return AuthDtos.TokenResponse.of(access, refresh,
                jwtProperties.accessTokenTtl().toSeconds(), toResponse(user));
    }

    private void publishUserRegistered(User user) {
        var event = new EventPayloads.UserRegistered(
                EventPayloads.EventMeta.now(MDC.get("traceId")),
                user.getId(), user.getEmail(), user.getFullName(), user.getRole().name());
        try {
            rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.USER_REGISTERED, event);
        } catch (Exception e) {
            // A broker problem must not fail the registration. The account is
            // created either way; the welcome notification is the thing lost,
            // and that is recoverable. The alternative — rolling back a
            // successful signup because RabbitMQ hiccupped — is worse.
            log.error("Could not publish user.registered for {}", user.getId(), e);
        }
    }

    private static AuthDtos.UserResponse toResponse(User user) {
        return new AuthDtos.UserResponse(user.getId(), user.getEmail(), user.getFullName(),
                user.getRole().name(), user.getCreatedAt());
    }
}
