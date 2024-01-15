package com.dinehub.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape every DineHub service returns.
 *
 * <p>One shape across every service is worth more than it looks: the Angular
 * client has one error handler rather than seven, and an operator reading logs
 * during an incident does not have to work out which service produced which
 * format.
 *
 * <p>{@code traceId} is the field that makes an error actionable — it is what
 * links a user's screenshot to the log lines in Loki.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String traceId,
        List<FieldViolation> violations
) {

    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path, String traceId) {
        return new ApiError(Instant.now(), status, error, message, path, traceId, null);
    }

    public static ApiError validation(String path, String traceId, List<FieldViolation> violations) {
        return new ApiError(Instant.now(), 400, "Bad Request",
                "Request validation failed", path, traceId, violations);
    }
}
