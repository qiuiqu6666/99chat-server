package com.chat99.server.wallet;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class WalletExceptions {

    private WalletExceptions() {}

    public static ResponseStatusException of(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }

    public static ResponseStatusException of(HttpStatus status, String code, String detail) {
        String reason = detail != null && !detail.isBlank() ? detail : code;
        return new ResponseStatusException(status, reason);
    }
}
