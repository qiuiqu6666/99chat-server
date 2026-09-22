package com.chat99.server.chatattachment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ChatAttachmentContentDisposition {

    private ChatAttachmentContentDisposition() {}

    public static String attachment(String originalName) {
        return build("attachment", originalName);
    }

    public static String inline(String originalName) {
        return build("inline", originalName);
    }

    private static String build(String type, String originalName) {
        String fallback = asciiFallback(originalName);
        if (originalName == null || originalName.isBlank()) {
            return type + "; filename=\"" + fallback + "\"";
        }
        String encoded = URLEncoder.encode(originalName.replace("\r", "").replace("\n", ""), StandardCharsets.UTF_8)
            .replace("+", "%20");
        return type + "; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static String asciiFallback(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "file";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < originalName.length(); i++) {
            char c = originalName.charAt(i);
            if (c < 32 || c == 127 || c == '"' || c == '\\' || c == ';' || c == '\r' || c == '\n') {
                continue;
            }
            if (c > 126) {
                continue;
            }
            sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "file" : out;
    }

    public static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 3
            && (bytes[0] & 0xff) == 0xff
            && (bytes[1] & 0xff) == 0xd8
            && (bytes[2] & 0xff) == 0xff;
    }

    public static String objectKey(String prefix, String ownerUserId, String attachmentId) {
        java.time.ZonedDateTime z = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC);
        return String.format(Locale.ROOT, "%s/%04d/%02d/%s/%s",
            prefix, z.getYear(), z.getMonthValue(), ownerUserId, attachmentId);
    }
}
