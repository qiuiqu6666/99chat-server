package com.chat99.server.sync;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * 通用 opaque cursor 工具：服务端内部用 {@code (seq, revision)} 元组，
 * 对外返回 base64url(json) 字符串。客户端不可解读、不可拼装，
 * 必须原样回传给服务端。
 *
 * <p>每个 domain（contacts / groups / groupMembers / groupNotices 等）的 cursor
 * 包含各自的 {@code seq + revision}。{@code payloadHash} 用于服务端校验
 * 防止客户端伪造不同 domain 的 cursor 跨域使用。
 */
public final class OpaqueCursor {

    public record CursorPayload(
        @JsonProperty("d") String domain,
        @JsonProperty("s") long seq,
        @JsonProperty("r") long revision,
        @JsonProperty("h") String payloadHash,
        @JsonProperty("k") String lastKey,
        @JsonProperty("t") Long total,
        @JsonProperty("e") Long emitted) {}

    private static final ObjectMapper M = new ObjectMapper();

    private OpaqueCursor() {}

    public static String encode(String domain, long seq, long revision) {
        return encode(new CursorPayload(domain, seq, revision, hashOf(domain, seq, revision), null, null, null));
    }

    public static String encode(String domain, long revision, String lastKey, long total, long emitted) {
        return encode(new CursorPayload(domain, 0L, revision,
            hashOf(domain, 0L, revision, lastKey, total, emitted), lastKey, total, emitted));
    }

    public static String encode(CursorPayload p) {
        try {
            byte[] json = M.writeValueAsBytes(p);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("encode opaque cursor", e);
        }
    }

    public static CursorPayload decode(String opaque) {
        if (opaque == null || opaque.isBlank()) {
            return new CursorPayload(null, 0L, 0L, "", null, null, null);
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(opaque);
            return M.readValue(new String(json, StandardCharsets.UTF_8), CursorPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid opaque cursor: " + e.getMessage());
        }
    }

    public static void validate(CursorPayload p, String expectedDomain) {
        if (p == null || p.domain() == null) {
            return;
        }
        if (!expectedDomain.equals(p.domain())) {
            throw new IllegalArgumentException("cursor domain mismatch: " + p.domain());
        }
        String expectedHash = p.lastKey() == null
            ? hashOf(p.domain(), p.seq(), p.revision())
            : hashOf(p.domain(), p.seq(), p.revision(), p.lastKey(),
                p.total() == null ? 0L : p.total(), p.emitted() == null ? 0L : p.emitted());
        if (p.payloadHash() != null && !p.payloadHash().isBlank()
            && !expectedHash.equals(p.payloadHash())) {
            throw new IllegalArgumentException("cursor payload hash mismatch");
        }
    }

    private static String hashOf(String domain, long seq, long revision) {
        return Integer.toHexString(Objects.hash(domain, Long.valueOf(seq), Long.valueOf(revision)));
    }

    private static String hashOf(String domain, long seq, long revision, String lastKey, long total, long emitted) {
        return Integer.toHexString(Objects.hash(domain, Long.valueOf(seq), Long.valueOf(revision), lastKey,
            Long.valueOf(total), Long.valueOf(emitted)));
    }

    public static String empty(String domain) {
        return encode(domain, 0L, 0L);
    }
}
