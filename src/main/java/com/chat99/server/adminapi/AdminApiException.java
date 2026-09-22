package com.chat99.server.adminapi;

import org.springframework.http.HttpStatus;

public class AdminApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public AdminApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }
}
