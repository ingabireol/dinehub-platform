package com.dinehub.common.security;

/** The three roles. Constants so a typo in a {@code @PreAuthorize} fails to compile. */
public final class Roles {

    private Roles() {
    }

    public static final String CUSTOMER = "CUSTOMER";
    public static final String KITCHEN = "KITCHEN";
    public static final String ADMIN = "ADMIN";

    public static final String HAS_CUSTOMER = "hasRole('" + CUSTOMER + "')";
    public static final String HAS_KITCHEN = "hasRole('" + KITCHEN + "')";
    public static final String HAS_ADMIN = "hasRole('" + ADMIN + "')";
    public static final String HAS_KITCHEN_OR_ADMIN =
            "hasAnyRole('" + KITCHEN + "','" + ADMIN + "')";
}
