package com.dinehub.common.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("GET", "/api/v1/orders/42");
        MDC.put("traceId", "trace-1234");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("not found maps to 404 with the message and trace id")
    void mapsNotFound() {
        var response = handler.handleNotFound(
                ApiExceptions.NotFoundException.of("Order", 42), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Order 42 not found");
        assertThat(response.getBody().traceId()).isEqualTo("trace-1234");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/orders/42");
    }

    @Test
    @DisplayName("bad request maps to 400")
    void mapsBadRequest() {
        var response = handler.handleBadRequest(
                new ApiExceptions.BadRequestException("Items must not be empty"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Items must not be empty");
    }

    @Test
    @DisplayName("conflict maps to 409")
    void mapsConflict() {
        var response = handler.handleConflict(
                new ApiExceptions.ConflictException("Order already paid"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("access denied maps to 403 without echoing the reason")
    void mapsForbidden() {
        // The internal reason may reveal what exists and what the caller is
        // missing; the client gets a flat "Access denied".
        var response = handler.handleForbidden(
                new AccessDeniedException("Required role ADMIN, had CUSTOMER"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo("Access denied");
        assertThat(response.getBody().message()).doesNotContain("ADMIN");
    }

    @Test
    @DisplayName("a validation failure lists each offending field")
    void mapsValidationErrors() throws Exception {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.rejectValue(null, "x");
        binding.addError(new org.springframework.validation.FieldError(
                "request", "price", "must be at least 0.01"));
        binding.addError(new org.springframework.validation.FieldError(
                "request", "name", "must not be blank"));

        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("mapsValidationErrors");
        var ex = new MethodArgumentNotValidException(new MethodParameter(method, -1), binding);

        var response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().violations())
                .extracting(ApiError.FieldViolation::field)
                .contains("price", "name");
    }

    @Test
    @DisplayName("a malformed body is a 400, not a 500")
    void mapsUnreadableBody() {
        var response = handler.handleUnreadable(
                new HttpMessageNotReadableException("boom",
                        new org.springframework.mock.http.MockHttpInputMessage(new byte[0])),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Malformed request body");
    }

    @Test
    @DisplayName("an unexpected exception returns 500 and leaks nothing")
    void hidesInternalDetail() {
        // A stack trace in an HTTP response hands an attacker the framework,
        // the version and often the file layout.
        var response = handler.handleUnexpected(
                new IllegalStateException("Connection to jdbc:postgresql://db-prod-01 refused"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().message()).doesNotContain("jdbc", "postgresql", "db-prod-01");
        // The trace id is how an operator finds the real detail in the log.
        assertThat(response.getBody().traceId()).isEqualTo("trace-1234");
    }

    @Test
    @DisplayName("an error raised outside a traced request still has a trace field")
    void handlesMissingTraceId() {
        MDC.clear();

        var response = handler.handleNotFound(
                new ApiExceptions.NotFoundException("gone"), request);

        assertThat(response.getBody().traceId()).isEqualTo("none");
    }
}
