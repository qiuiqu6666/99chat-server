package com.chat99.server.adminapi;

import java.util.List;

public record AdminPrincipal(String username, List<String> permissions) {

    public boolean hasPermission(String permission) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        if (permissions.contains("admin.manage") || permissions.contains("*")) {
            return true;
        }
        return permissions.contains(permission);
    }
}
