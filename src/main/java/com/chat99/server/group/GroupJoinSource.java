package com.chat99.server.group;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum GroupJoinSource {
    group_alias,
    qr_code,
    search;

    public static GroupJoinSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }
}
