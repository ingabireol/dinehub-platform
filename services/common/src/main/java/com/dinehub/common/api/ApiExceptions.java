package com.dinehub.common.api;

/**
 * The exceptions services throw to produce a specific HTTP status.
 *
 * <p>Grouped in one file on purpose: there are only four of them, and the set
 * being small and visible is what stops a fifth being invented each time
 * somebody needs a 409.
 */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** 404 — the thing asked for does not exist. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }

        public static NotFoundException of(String type, Object id) {
            return new NotFoundException("%s %s not found".formatted(type, id));
        }
    }

    /** 400 — the request is well-formed but asks for something invalid. */
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /**
     * 409 — the request conflicts with the current state.
     *
     * <p>Distinct from 400 deliberately: a client that gets 400 should change
     * the request, a client that gets 409 should re-read the state first. That
     * difference matters for an order whose status has moved on.
     */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** 403 — authenticated, but not allowed to do this. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }
}
