package com.chat99.server.chatattachment;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChatMediaMetadata {

    static final long MAX_DURATION_MS = 24L * 3600L * 1000L;
    private static final Logger log = LoggerFactory.getLogger(ChatMediaMetadata.class);

    private ChatMediaMetadata() {}

    static Long sanitizeDurationMs(Long raw) {
        if (raw == null || raw <= 0L) {
            return null;
        }
        if (raw > MAX_DURATION_MS) {
            log.warn("chat media durationMs out of range value={}", raw);
            return null;
        }
        return raw;
    }

    static Integer sanitizePx(Integer raw) {
        if (raw == null || raw <= 0) {
            return null;
        }
        return raw;
    }

    static Long durationForKind(ChatAttachmentKind kind, Long raw) {
        if (kind != ChatAttachmentKind.video && kind != ChatAttachmentKind.audio) {
            return null;
        }
        return sanitizeDurationMs(raw);
    }

    static long videoSecond(Long durationMs) {
        Long sanitized = sanitizeDurationMs(durationMs);
        if (sanitized == null) {
            return 0L;
        }
        return (sanitized + 999L) / 1000L;
    }

    static ChatMetadataProvenance effectiveSource(ChatAttachment attachment) {
        if (attachment.getMediaProbeSource() != null) {
            return attachment.getMediaProbeSource();
        }
        if (attachment.getMetadataProvenance() != null) {
            return attachment.getMetadataProvenance();
        }
        if (sanitizeDurationMs(attachment.getDurationMs()) != null) {
            return ChatMetadataProvenance.client;
        }
        return ChatMetadataProvenance.fallback;
    }

    static void markSource(ChatAttachment attachment, ChatMetadataProvenance source) {
        Instant now = Instant.now();
        attachment.setMediaProbeSource(source);
        attachment.setMetadataProvenance(source);
        attachment.setMediaProbedAt(now);
    }
}
