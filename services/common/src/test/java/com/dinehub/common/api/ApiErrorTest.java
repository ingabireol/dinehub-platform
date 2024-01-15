package com.dinehub.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorTest {

    @Test
    @DisplayName("a plain error carries status, message, path and trace id")
    void buildsPlainError() {
        ApiError error = ApiError.of(404, "Not Found", "Order 42 not found",
                "/api/v1/orders/42", "abc123");

        assertThat(error.status()).isEqualTo(404);
        assertThat(error.message()).isEqualTo("Order 42 not found");
        assertThat(error.path()).isEqualTo("/api/v1/orders/42");
        assertThat(error.traceId()).isEqualTo("abc123");
        assertThat(error.violations()).isNull();
        assertThat(error.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("a validation error lists the offending fields")
    void buildsValidationError() {
        ApiError error = ApiError.validation("/api/v1/orders", "abc123",
                List.of(new ApiError.FieldViolation("items", "must not be empty")));

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.violations()).hasSize(1);
        assertThat(error.violations().getFirst().field()).isEqualTo("items");
    }

    @Test
    @DisplayName("the exception helpers produce a readable message")
    void notFoundHelperReadsWell() {
        var ex = ApiExceptions.NotFoundException.of("Order", 42);
        assertThat(ex).hasMessage("Order 42 not found");
    }
}
