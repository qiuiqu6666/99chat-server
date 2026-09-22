package com.chat99.server.chatattachment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ChatAttachmentRouting {

    private ChatAttachmentRouting() {}

    public static boolean requiresSelfHosted(long sizeBytes,
                                             ChatNativeMessageKind nativeKind,
                                             ChatAttachmentProperties props) {
        long nativeMax = nativeMaxBytes(nativeKind, props);
        long threshold = Math.min(props.routingThresholdBytes(), nativeMax);
        return sizeBytes > threshold;
    }

    public static long nativeMaxBytes(ChatNativeMessageKind nativeKind, ChatAttachmentProperties props) {
        ChatAttachmentProperties.NativeMax n = props.nativeMaxBytes();
        return switch (nativeKind) {
            case image -> n.image();
            case sound -> n.sound();
            case video -> n.video();
            case file -> n.file();
        };
    }

    public static void validateKindPair(ChatAttachmentKind kind, ChatNativeMessageKind nativeKind) {
        boolean ok = switch (kind) {
            case image -> nativeKind == ChatNativeMessageKind.image;
            case video -> nativeKind == ChatNativeMessageKind.video;
            case file -> nativeKind == ChatNativeMessageKind.file;
            case audio -> nativeKind == ChatNativeMessageKind.sound
                || nativeKind == ChatNativeMessageKind.file;
        };
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    public static int expectedPartCount(long sizeBytes, long partSizeBytes) {
        if (sizeBytes <= 0 || partSizeBytes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        long count = (sizeBytes + partSizeBytes - 1) / partSizeBytes;
        if (count > 256) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        return (int) count;
    }

    public static String sanitizeOriginalName(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace("\r", "").replace("\n", "").trim();
        if (cleaned.length() > 255) {
            cleaned = cleaned.substring(0, 255);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }
}
