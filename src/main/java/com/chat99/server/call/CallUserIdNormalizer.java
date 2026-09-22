package com.chat99.server.call;

public final class CallUserIdNormalizer {

    private CallUserIdNormalizer() {}

    public static String normalize(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("c2c_")) {
            text = text.substring(4).trim();
        }
        int hashIndex = text.indexOf('#');
        if (hashIndex > 0) {
            text = text.substring(0, hashIndex).trim();
        }
        return text;
    }

    public static boolean isValidVoipDisplayName(String displayName, String normalizedCallerId) {
        if (displayName == null || displayName.isBlank()) {
            return false;
        }
        String trimmed = displayName.trim();
        if (trimmed.contains("#")) {
            return false;
        }
        if (normalizedCallerId == null || normalizedCallerId.isBlank()) {
            return true;
        }
        if (trimmed.equals(normalizedCallerId)) {
            return false;
        }
        return !trimmed.equals("c2c_" + normalizedCallerId);
    }

    public static boolean wasCompositeId(String raw, String normalized) {
        return raw != null && !raw.isBlank() && !raw.trim().equals(normalized);
    }
}
