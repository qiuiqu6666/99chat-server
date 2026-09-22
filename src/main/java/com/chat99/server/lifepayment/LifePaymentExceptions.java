package com.chat99.server.lifepayment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class LifePaymentExceptions {

    private LifePaymentExceptions() {}

    public static ResponseStatusException of(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }

    public static ResponseStatusException badRequest(String code) {
        return of(HttpStatus.BAD_REQUEST, code);
    }

    public static ResponseStatusException badRequest(String code, String ignoredDetail) {
        return of(HttpStatus.BAD_REQUEST, code);
    }

    public static ResponseStatusException conflict(String code) {
        return of(HttpStatus.CONFLICT, code);
    }

    public static ResponseStatusException notFound(String code) {
        return of(HttpStatus.NOT_FOUND, code);
    }

    public static ResponseStatusException forbidden(String code) {
        return of(HttpStatus.FORBIDDEN, code);
    }
}
