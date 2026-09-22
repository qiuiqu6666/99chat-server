package com.chat99.server.chatattachment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class ChatAttachmentConversationIds {

    private ChatAttachmentConversationIds() {}

    public record ConversationIdentity(
        ChatConversationType type,
        String conversationKey,
        String participantLow,
        String participantHigh,
        String groupId
    ) {}

    public static ConversationIdentity c2c(String selfUserId, String peerUserId) {
        String self = requireId(selfUserId, "INVALID_INPUT");
        String peer = requireId(peerUserId, "INVALID_INPUT");
        if (self.equals(peer)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        byte[] selfBytes = self.getBytes(StandardCharsets.UTF_8);
        byte[] peerBytes = peer.getBytes(StandardCharsets.UTF_8);
        boolean selfLow = unsignedCompare(selfBytes, peerBytes) <= 0;
        String low = selfLow ? self : peer;
        String high = selfLow ? peer : self;
        byte[] lowBytes = selfLow ? selfBytes : peerBytes;
        byte[] highBytes = selfLow ? peerBytes : selfBytes;
        byte[] canonical = concatLengthPrefixed(lowBytes, highBytes);
        return new ConversationIdentity(
            ChatConversationType.c2c,
            "c2c:v1:" + sha256Hex(canonical),
            low,
            high,
            null);
    }

    public static ConversationIdentity group(String groupId) {
        String gid = requireId(groupId, "INVALID_INPUT");
        return new ConversationIdentity(
            ChatConversationType.group,
            "group:v1:" + gid,
            null,
            null,
            gid);
    }

    static int unsignedCompare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int av = a[i] & 0xff;
            int bv = b[i] & 0xff;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    static byte[] concatLengthPrefixed(byte[] first, byte[] second) {
        ByteBuffer buf = ByteBuffer.allocate(8 + first.length + second.length);
        buf.putInt(first.length);
        buf.put(first);
        buf.putInt(second.length);
        buf.put(second);
        return buf.array();
    }

    private static String sha256Hex(byte[] input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String requireId(String raw, String code) {
        if (raw == null || raw.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, code);
        }
        return raw;
    }
}
