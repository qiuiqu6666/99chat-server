package com.chat99.server.sticker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses 99chat custom sticker references from Tencent IM {@code TIMFaceElem} payloads.
 */
public final class StickerImMessageSupport {

    public static final int CUSTOM_FACE_INDEX = 99;
    private static final String FACE_MSG_TYPE = "TIMFaceElem";
    private static final String STICKER_SCHEME_PREFIX = "99chat://sticker/";

    private StickerImMessageSupport() {}

    public static List<String> extractStickerIds(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        Object msgBodyRaw = body.get("MsgBody");
        if (!(msgBodyRaw instanceof List<?> msgBody) || msgBody.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : msgBody) {
            if (!(item instanceof Map<?, ?> msg)) {
                continue;
            }
            if (!FACE_MSG_TYPE.equals(str(msg.get("MsgType")))) {
                continue;
            }
            Object contentRaw = msg.get("MsgContent");
            if (!(contentRaw instanceof Map<?, ?> content)) {
                continue;
            }
            if (parseIndex(content.get("Index")) != CUSTOM_FACE_INDEX) {
                continue;
            }
            parseStickerIdFromFaceData(str(content.get("Data"))).ifPresent(ids::add);
        }
        return ids;
    }

    public static Optional<String> parseStickerIdFromFaceData(String data) {
        if (data == null || data.isBlank()) {
            return Optional.empty();
        }
        String trimmed = data.trim();
        if (!trimmed.startsWith(STICKER_SCHEME_PREFIX)) {
            return Optional.empty();
        }
        String rest = trimmed.substring(STICKER_SCHEME_PREFIX.length());
        int end = rest.length();
        int query = rest.indexOf('?');
        if (query >= 0) {
            end = Math.min(end, query);
        }
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            end = Math.min(end, hash);
        }
        String stickerId = rest.substring(0, end).trim();
        return stickerId.isBlank() ? Optional.empty() : Optional.of(stickerId);
    }

    private static int parseIndex(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
