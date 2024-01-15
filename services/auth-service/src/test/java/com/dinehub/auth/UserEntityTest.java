package com.dinehub.auth;

import com.dinehub.auth.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserEntityTest {

    @Test
    @DisplayName("an email is normalised to lower case on construction")
    void lowercasesEmail() {
        // The unique index is on LOWER(email). If the entity stored mixed case,
        // lookups by the stored value would miss.
        var user = new User("Mixed.Case@Example.COM", "$2a$12$hash",
                "Mixed Case", User.Role.CUSTOMER);

        assertThat(user.getEmail()).isEqualTo("mixed.case@example.com");
    }

    @Test
    @DisplayName("a new user is enabled and has an id and timestamp")
    void newUserDefaults() {
        var user = new User("new@example.com", "$2a$12$hash", "New", User.Role.KITCHEN);

        assertThat(user.getId()).isNotNull();
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getRole()).isEqualTo(User.Role.KITCHEN);
    }

    @Test
    @DisplayName("an account can be disabled and its password changed")
    void supportsAdministrativeChanges() {
        var user = new User("admin@example.com", "$2a$12$old", "Admin", User.Role.ADMIN);

        user.setEnabled(false);
        user.changePassword("$2a$12$new");

        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("$2a$12$new");
    }
}
