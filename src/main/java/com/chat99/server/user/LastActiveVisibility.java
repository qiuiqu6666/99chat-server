package com.chat99.server.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 最后上线时间对谁可见 */
public enum LastActiveVisibility {
    /** 所有人可查看 */
    everyone,
    /** 仅双向好友可查看 */
    friends_only,
    /** 不显示在线时间 */
    hidden;

    public static LastActiveVisibility parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        try {
            return valueOf(raw.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }
}
