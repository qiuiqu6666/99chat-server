package com.chat99.server.clientversion;

import java.util.Locale;

public enum ClientVersionUpdateType {
    NONE,
    OPTIONAL,
    FORCE,
    GRAY;

    public static ClientVersionUpdateType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPTIONAL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none", "0", "false" -> NONE;
            case "optional", "soft" -> OPTIONAL;
            case "force", "forced", "required", "1", "true" -> FORCE;
            case "gray", "grey", "canary" -> GRAY;
            default -> null;
        };
    }

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
