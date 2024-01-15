package com.dinehub.auth;

import com.dinehub.auth.dto.AuthDtos;
import com.dinehub.auth.entity.User;
import com.dinehub.auth.repository.UserRepository;
import com.dinehub.auth.service.AuthService;
import com.dinehub.common.api.ApiExceptions;
import com.dinehub.common.security.JwtProperties;
import com.dinehub.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough-for-hs256";
    private static final String GOOD_PASSWORD = "CorrectHorse1Battery";

    @Mock
    private UserRepository users;

    @Mock
    private RabbitTemplate rabbit;

    private AuthService authService;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        // Cost 4 in tests. Cost 12 is correct in production and makes a test
        // suite with a dozen hashing operations take several seconds.
        encoder = new BCryptPasswordEncoder(4);
        JwtProperties properties = new JwtProperties(
                SECRET, Duration.ofMinutes(15), Duration.ofDays(7), "dinehub");
        authService = new AuthService(users, encoder, new JwtService(properties), properties, rabbit);
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("creates the account and returns tokens")
        void registersSuccessfully() {
            when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            var response = authService.register(new AuthDtos.RegisterRequest(
                    "New.User@Example.com", GOOD_PASSWORD, "New User", null));

            assertThat(response.accessToken()).isNotBlank();
            assertThat(response.refreshToken()).isNotBlank();
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.user().email()).isEqualTo("new.user@example.com");
        }

        @Test
        @DisplayName("ignores a role supplied by the client")
        void refusesClientSuppliedRole() {
            // Honouring this field would be privilege escalation by JSON.
            when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            var response = authService.register(new AuthDtos.RegisterRequest(
                    "sneaky@example.com", GOOD_PASSWORD, "Sneaky", "ADMIN"));

            assertThat(response.user().role()).isEqualTo("CUSTOMER");

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(users).save(saved.capture());
            assertThat(saved.getValue().getRole()).isEqualTo(User.Role.CUSTOMER);
        }

        @Test
        @DisplayName("stores a hash, never the password")
        void storesOnlyAHash() {
            when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            authService.register(new AuthDtos.RegisterRequest(
                    "hash@example.com", GOOD_PASSWORD, "Hash Me", null));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(users).save(saved.capture());
            assertThat(saved.getValue().getPasswordHash())
                    .doesNotContain(GOOD_PASSWORD)
                    .startsWith("$2");
            assertThat(encoder.matches(GOOD_PASSWORD, saved.getValue().getPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("rejects a duplicate email with 409")
        void rejectsDuplicate() {
            when(users.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(new AuthDtos.RegisterRequest(
                    "taken@example.com", GOOD_PASSWORD, "Taken", null)))
                    .isInstanceOf(ApiExceptions.ConflictException.class);

            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("still succeeds when the broker is unavailable")
        void survivesBrokerOutage() {
            // The account is created either way. Losing the welcome notification
            // is recoverable; rolling back a successful signup because RabbitMQ
            // hiccupped is not acceptable.
            when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
            doThrow(new AmqpException("broker down"))
                    .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

            var response = authService.register(new AuthDtos.RegisterRequest(
                    "resilient@example.com", GOOD_PASSWORD, "Resilient", null));

            assertThat(response.accessToken()).isNotBlank();
            verify(users).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        private User existing;

        @BeforeEach
        void createUser() {
            existing = new User("known@example.com", encoder.encode(GOOD_PASSWORD),
                    "Known User", User.Role.CUSTOMER);
        }

        @Test
        @DisplayName("issues tokens for correct credentials")
        void succeedsWithCorrectPassword() {
            when(users.findByEmailIgnoreCase("known@example.com")).thenReturn(Optional.of(existing));

            var response = authService.login(
                    new AuthDtos.LoginRequest("known@example.com", GOOD_PASSWORD));

            assertThat(response.accessToken()).isNotBlank();
            assertThat(response.user().email()).isEqualTo("known@example.com");
        }

        @Test
        @DisplayName("gives the same message for a wrong password and an unknown account")
        void doesNotRevealWhetherTheAccountExists() {
            // Distinguishing these turns login into an account enumeration oracle.
            when(users.findByEmailIgnoreCase("known@example.com")).thenReturn(Optional.of(existing));
            when(users.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

            String wrongPassword = catchMessage(() -> authService.login(
                    new AuthDtos.LoginRequest("known@example.com", "WrongPassword123")));
            String noSuchUser = catchMessage(() -> authService.login(
                    new AuthDtos.LoginRequest("ghost@example.com", GOOD_PASSWORD)));

            assertThat(wrongPassword).isEqualTo(noSuchUser).isEqualTo("Invalid email or password");
        }

        @Test
        @DisplayName("refuses a disabled account")
        void refusesDisabledAccount() {
            existing.setEnabled(false);
            when(users.findByEmailIgnoreCase("known@example.com")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> authService.login(
                    new AuthDtos.LoginRequest("known@example.com", GOOD_PASSWORD)))
                    .isInstanceOf(ApiExceptions.ForbiddenException.class)
                    .hasMessageContaining("disabled");
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        /**
         * register() builds its own User and uses that instance, so the id in
         * the issued token is the one the entity generated — not whatever the
         * save() stub happens to return. The captor is how the test learns it.
         */
        private User registerAndCaptureLast() {
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(users).save(saved.capture());
            return saved.getValue();
        }

        private User registerAndCapture(String email) {
            when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            authService.register(new AuthDtos.RegisterRequest(
                    email, GOOD_PASSWORD, "Refresh User", null));
            verify(users).save(saved.capture());
            return saved.getValue();
        }

        /**
         * The lookup stub is wired only where the code path reaches it. Mockito
         * runs in strict mode, so an unused stub fails the test — which is the
         * behaviour we want: it catches a test that is not exercising what its
         * name claims.
         */
        private AuthDtos.TokenResponse registerAndWireLookup(String email) {
            when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            var response = authService.register(new AuthDtos.RegisterRequest(
                    email, GOOD_PASSWORD, "Refresh User", null));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(users).save(saved.capture());
            User persisted = saved.getValue();
            when(users.findById(persisted.getId())).thenReturn(Optional.of(persisted));

            return response;
        }

        @Test
        @DisplayName("exchanges a valid refresh token for a new access token")
        void refreshesSuccessfully() {
            var registered = registerAndWireLookup("refresh@example.com");

            var refreshed = authService.refresh(
                    new AuthDtos.RefreshRequest(registered.refreshToken()));

            assertThat(refreshed.accessToken()).isNotBlank();
            assertThat(refreshed.user().email()).isEqualTo("refresh@example.com");
        }

        @Test
        @DisplayName("refuses an access token presented as a refresh token")
        void refusesAccessTokenAsRefresh() {
            // No user lookup is wired: the type check must reject this before
            // the service ever touches the repository.
            when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
            var registered = authService.register(new AuthDtos.RegisterRequest(
                    "swap@example.com", GOOD_PASSWORD, "Swap", null));

            assertThatThrownBy(() -> authService.refresh(
                    new AuthDtos.RefreshRequest(registered.accessToken())))
                    .isInstanceOf(ApiExceptions.ForbiddenException.class)
                    .hasMessageContaining("Not a refresh token");
        }

        @Test
        @DisplayName("refuses to refresh a disabled account")
        void refusesDisabledAccount() {
            // Checked on every refresh, not only at login — otherwise disabling
            // an account leaves it working for the whole refresh-token lifetime.
            var registered = registerAndWireLookup("disabled@example.com");
            User persisted = registerAndCaptureLast();
            persisted.setEnabled(false);

            assertThatThrownBy(() -> authService.refresh(
                    new AuthDtos.RefreshRequest(registered.refreshToken())))
                    .isInstanceOf(ApiExceptions.ForbiddenException.class)
                    .hasMessageContaining("disabled");
        }

        @Test
        @DisplayName("refuses a malformed refresh token")
        void refusesGarbage() {
            assertThatThrownBy(() -> authService.refresh(
                    new AuthDtos.RefreshRequest("not-a-jwt")))
                    .isInstanceOf(ApiExceptions.ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("current user returns 404 when the account is gone")
    void currentUserNotFound() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.currentUser(id))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected the call to fail");
        } catch (ApiExceptions.ForbiddenException e) {
            return e.getMessage();
        }
    }
}
