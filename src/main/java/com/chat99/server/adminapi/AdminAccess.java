package com.chat99.server.adminapi;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

public final class AdminAccess {

    private AdminAccess() {}

    public static AdminPrincipal require(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AdminPrincipal p)) {
            throw new AdminApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "invalid_token");
        }
        return p;
    }

    public static void requirePermission(Authentication auth, String permission) {
        AdminPrincipal p = require(auth);
        if (!p.hasPermission(permission)) {
            throw new AdminApiException(HttpStatus.FORBIDDEN, "forbidden", "forbidden");
        }
    }
}
