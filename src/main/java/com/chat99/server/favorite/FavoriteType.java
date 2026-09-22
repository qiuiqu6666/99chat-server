package com.chat99.server.favorite;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Favorite media type. Accepts API strings ({@code TEXT}/{@code IMAGE}/{@code VIDEO})
 * and Tencent IM {@code elemType} values ({@code 1}=text, {@code 3}=image, {@code 5}=video).
 */
public enum FavoriteType {
    TEXT,
    IMAGE,
    VIDEO;

    /** Tencent IM V2TIM element types used by many clients when forwarding chat messages. */
    public static FavoriteType fromImElemType(int elemType) {
        return switch (elemType) {
            case 1 -> TEXT;
            case 3 -> IMAGE;
            case 5 -> VIDEO;
            default -> null;
        };
    }

    @JsonCreator
    public static FavoriteType fromJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return fromImElemType(n.intValue());
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            try {
                return fromImElemType(Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String v = raw.toUpperCase();
        return switch (v) {
            case "0", "TEXT", "TXT" -> TEXT;
            case "IMAGE", "IMG", "PHOTO", "PICTURE" -> IMAGE;
            case "VIDEO", "VID" -> VIDEO;
            default -> {
                try {
                    yield FavoriteType.valueOf(v);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    /** Infer type when JSON omits {@code type} but other fields are present. */
    public static FavoriteType inferFromPayload(String text, String remoteMediaUrl, Integer durationSec) {
        if (text != null && !text.isBlank()
            && (remoteMediaUrl == null || remoteMediaUrl.isBlank())) {
            return TEXT;
        }
        if (remoteMediaUrl != null && !remoteMediaUrl.isBlank()) {
            return durationSec != null && durationSec > 0 ? VIDEO : IMAGE;
        }
        return null;
    }
}
