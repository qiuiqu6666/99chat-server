package com.chat99.server.clientversion;

import java.util.Locale;

public enum ClientVersionPlatform {
    ANDROID,
    IOS,
    WEB;

    public static ClientVersionPlatform parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "android" -> ANDROID;
            case "ios", "iphone", "ipad" -> IOS;
            case "web", "h5" -> WEB;
            default -> null;
        };
    }

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
